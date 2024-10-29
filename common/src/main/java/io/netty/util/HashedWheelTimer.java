/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util;

import static io.netty.util.internal.ObjectUtil.checkInRange;
import static io.netty.util.internal.ObjectUtil.checkPositive;
import static io.netty.util.internal.ObjectUtil.checkNotNull;

import io.netty.util.concurrent.ImmediateExecutor;
import io.netty.util.internal.MathUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

import static io.netty.util.internal.StringUtil.simpleClassName;

/**
 * A {@link Timer} optimized for approximated I/O timeout scheduling.
 *
 * <h3>Tick Duration</h3>
 *
 * As described with 'approximated', this timer does not execute the scheduled
 * {@link TimerTask} on time.  {@link HashedWheelTimer}, on every tick, will
 * check if there are any {@link TimerTask}s behind the schedule and execute
 * them.
 * <p>
 * You can increase or decrease the accuracy of the execution timing by
 * specifying smaller or larger tick duration in the constructor.  In most
 * network applications, I/O timeout does not need to be accurate.  Therefore,
 * the default tick duration is 100 milliseconds and you will not need to try
 * different configurations in most cases.
 *
 * <h3>Ticks per Wheel (Wheel Size)</h3>
 *
 * {@link HashedWheelTimer} maintains a data structure called 'wheel'.
 * To put simply, a wheel is a hash table of {@link TimerTask}s whose hash
 * function is 'dead line of the task'.  The default number of ticks per wheel
 * (i.e. the size of the wheel) is 512.  You could specify a larger value
 * if you are going to schedule a lot of timeouts.
 *
 * <h3>Do not create many instances.</h3>
 *
 * {@link HashedWheelTimer} creates a new thread whenever it is instantiated and
 * started.  Therefore, you should make sure to create only one instance and
 * share it across your application.  One of the common mistakes, that makes
 * your application unresponsive, is to create a new instance for every connection.
 *
 * <h3>Implementation Details</h3>
 *
 * {@link HashedWheelTimer} is based on
 * <a href="https://cseweb.ucsd.edu/users/varghese/">George Varghese</a> and
 * Tony Lauck's paper,
 * <a href="https://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
 * and Hierarchical Timing Wheels: data structures to efficiently implement a
 * timer facility'</a>.  More comprehensive slides are located
 * <a href="https://www.cse.wustl.edu/~cdgill/courses/cs6874/TimingWheels.ppt">here</a>.
 */
public class HashedWheelTimer implements Timer {

    static final InternalLogger logger =
            InternalLoggerFactory.getInstance(HashedWheelTimer.class);

    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
    private static final AtomicBoolean WARNED_TOO_MANY_INSTANCES = new AtomicBoolean();
    private static final int INSTANCE_COUNT_LIMIT = 64;
    private static final long MILLISECOND_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final ResourceLeakDetector<HashedWheelTimer> leakDetector = ResourceLeakDetectorFactory.instance()
            .newResourceLeakDetector(HashedWheelTimer.class, 1);

    private static final AtomicIntegerFieldUpdater<HashedWheelTimer> WORKER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimer.class, "workerState");

    private final ResourceLeakTracker<HashedWheelTimer> leak;
    private final Worker worker = new Worker();
    private final Thread workerThread;

    public static final int WORKER_STATE_INIT = 0;
    public static final int WORKER_STATE_STARTED = 1;
    public static final int WORKER_STATE_SHUTDOWN = 2;
    @SuppressWarnings({"unused", "FieldMayBeFinal"})
    private volatile int workerState; // 0 - init, 1 - started, 2 - shut down

    private final long tickDuration;
    private final HashedWheelBucket[] wheel;
    private final int mask;
    private final CountDownLatch startTimeInitialized = new CountDownLatch(1);
    private final Queue<HashedWheelTimeout> timeouts = PlatformDependent.newMpscQueue();
    private final Queue<HashedWheelTimeout> cancelledTimeouts = PlatformDependent.newMpscQueue();
    private final AtomicLong pendingTimeouts = new AtomicLong(0);
    private final long maxPendingTimeouts;
    private final Executor taskExecutor;

    private volatile long startTime;

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}), default tick duration, and
     * default number of ticks per wheel.
     */
    public HashedWheelTimer() {
        this(Executors.defaultThreadFactory());
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}) and default number of ticks
     * per wheel.
     *
     * @param tickDuration the duration between tick
     * @param unit         the time unit of the {@code tickDuration}
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit) {
        this(Executors.defaultThreadFactory(), tickDuration, unit);
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}).
     *
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(Executors.defaultThreadFactory(), tickDuration, unit, ticksPerWheel);
    }

    /**
     * Creates a new timer with the default tick duration and default number of
     * ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public HashedWheelTimer(ThreadFactory threadFactory) {
        this(threadFactory, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new timer with the default number of ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory, long tickDuration, TimeUnit unit) {
        this(threadFactory, tickDuration, unit, 512);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, true);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @param leakDetection {@code true} if leak detection should be enabled always,
     *                      if false it will only be enabled if the worker thread is not
     *                      a daemon thread.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel, boolean leakDetection) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, leakDetection, -1);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory        a {@link ThreadFactory} that creates a
     *                             background {@link Thread} which is dedicated to
     *                             {@link TimerTask} execution.
     * @param tickDuration         the duration between tick
     * @param unit                 the time unit of the {@code tickDuration}
     * @param ticksPerWheel        the size of the wheel
     * @param leakDetection        {@code true} if leak detection should be enabled always,
     *                             if false it will only be enabled if the worker thread is not
     *                             a daemon thread.
     * @param  maxPendingTimeouts  The maximum number of pending timeouts after which call to
     *                             {@code newTimeout} will result in
     *                             {@link java.util.concurrent.RejectedExecutionException}
     *                             being thrown. No maximum pending timeouts limit is assumed if
     *                             this value is 0 or negative.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel, boolean leakDetection,
            long maxPendingTimeouts) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, leakDetection,
                maxPendingTimeouts, ImmediateExecutor.INSTANCE);
    }
    /**
     * Creates a new timer.
     *
     * @param threadFactory        a {@link ThreadFactory} that creates a
     *                             background {@link Thread} which is dedicated to
     *                             {@link TimerTask} execution.
     * @param tickDuration         the duration between tick
     * @param unit                 the time unit of the {@code tickDuration}
     * @param ticksPerWheel        the size of the wheel
     * @param leakDetection        {@code true} if leak detection should be enabled always,
     *                             if false it will only be enabled if the worker thread is not
     *                             a daemon thread.
     * @param maxPendingTimeouts   The maximum number of pending timeouts after which call to
     *                             {@code newTimeout} will result in
     *                             {@link java.util.concurrent.RejectedExecutionException}
     *                             being thrown. No maximum pending timeouts limit is assumed if
     *                             this value is 0 or negative.
     * @param taskExecutor         The {@link Executor} that is used to execute the submitted {@link TimerTask}s.
     *                             The caller is responsible to shutdown the {@link Executor} once it is not needed
     *                             anymore.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory, // 线程工厂
            long tickDuration, // 时间轮的执行是定时取轮子上的任务，这就是哪个定时时间，默认100ms
            TimeUnit unit,  // 定时的时间单位
            int ticksPerWheel, // 时间轮的长度，默认是512，会统一给你封装为2的n次方
            boolean leakDetection,
            long maxPendingTimeouts,
            Executor taskExecutor) {

        checkNotNull(threadFactory, "threadFactory");
        checkNotNull(unit, "unit");
        checkPositive(tickDuration, "tickDuration");
        checkPositive(ticksPerWheel, "ticksPerWheel");
        this.taskExecutor = checkNotNull(taskExecutor, "taskExecutor");

        // Normalize ticksPerWheel to power of two and initialize the wheel.
        // 创建hash轮，根据你传人的长度来创建，如果不是2的n次方，他会向上取最近的2的n次方，为了hash运算做位运算取代取模
        // 这个创建出来，其实就是填充了一个HashedWheelBucket的数组，每个格子里面是一个HashedWheelBucket的链表
        // 这个数组的长度是2的n次方，值是ticksPerWheel向上取2的n次方的最小值
        wheel = createWheel(ticksPerWheel);
        // 数组的长度-1，这个mask是用来做取模用的,2的n次方-1，位运算替代取模用的
        mask = wheel.length - 1;

        // Convert tickDuration to nanos. 定时时间转为了纳秒，内部统一转换单位
        long duration = unit.toNanos(tickDuration);

        // Prevent overflow. 防止溢出，校验了一下长度，这个时间间隔不能太长
        if (duration >= Long.MAX_VALUE / wheel.length) {
            throw new IllegalArgumentException(String.format(
                    "tickDuration: %d (expected: 0 < tickDuration in nanos < %d",
                    tickDuration, Long.MAX_VALUE / wheel.length));
        }

        // 扫描间隔也不能太短，不能小于1ms
        if (duration < MILLISECOND_NANOS) {
            logger.warn("Configured tickDuration {} smaller than {}, using 1ms.",
                        tickDuration, MILLISECOND_NANOS);
            this.tickDuration = MILLISECOND_NANOS;
        } else {
            this.tickDuration = duration;
        }

        // 这个扫描轮上的任务是一个线程跑的，这就是哪个worker线程
        workerThread = threadFactory.newThread(worker);

        // 线程泄露检测是否开启，如果你配置了开启true，那么就开启
        // 如果你配置了不开启，那么就要看这个worker线程是不是守护线程，如果不是守护线程，那么就开启检测这个线程
        leak = leakDetection || !workerThread.isDaemon() ? leakDetector.track(this) : null;

        // worker扫描任务，在当前任务上话费的最大时间，避免他一直卡在一个任务，轮子后面的任务就得不到执行
        this.maxPendingTimeouts = maxPendingTimeouts;

        /**
         * 每当你创建一个hashedWheelTimer，那么就会创建一个实例，这个计数器记录有多少个实例。如果你在项目中创建超过了
         * 默认值INSTANCE_COUNT_LIMIT(64)，并且WARNED_TOO_MANY_INSTANCES从false变为true，那么就会执行reportTooManyInstances
         * 打印一个警告信息，告诉你创建的实例太多了。其实就是建议你别搞太多，负载会拉高影响你的程序运行。这个值默认是64，你可以通过
         * SystemProperty.java中设置这个值，比如-Dio.netty.hashedWheelTimerWarnTooManyInstances=false
         */
        if (INSTANCE_COUNTER.incrementAndGet() > INSTANCE_COUNT_LIMIT &&
            WARNED_TOO_MANY_INSTANCES.compareAndSet(false, true)) {
            reportTooManyInstances();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            // This object is going to be GCed and it is assumed the ship has sailed to do a proper shutdown. If
            // we have not yet shutdown then we want to make sure we decrement the active instance count.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }
        }
    }

    private static HashedWheelBucket[] createWheel(int ticksPerWheel) {
        ticksPerWheel = MathUtil.findNextPositivePowerOfTwo(ticksPerWheel);

        HashedWheelBucket[] wheel = new HashedWheelBucket[ticksPerWheel];
        for (int i = 0; i < wheel.length; i ++) {
            wheel[i] = new HashedWheelBucket();
        }
        return wheel;
    }

    /**
     * Starts the background thread explicitly.  The background thread will
     * start automatically on demand even if you did not call this method.
     *
     * @throws IllegalStateException if this timer has been
     *                               {@linkplain #stop() stopped} already
     */
    public void start() {
        // 看我worker线程的状态，如果是初始状态，那么就执行compareAndSet方法，如果成功，那么就启动worker线程
        // 其他状态的话如果起来了就不执行，如果关闭了，就直接抛异常，保证我线程只启动一次，一个时间轮就一个worker线程
        switch (WORKER_STATE_UPDATER.get(this)) {
            case WORKER_STATE_INIT:
                if (WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_INIT, WORKER_STATE_STARTED)) {
                    // 执行run方法
                    workerThread.start();
                }
                break;
            case WORKER_STATE_STARTED:
                break;
            case WORKER_STATE_SHUTDOWN:
                throw new IllegalStateException("cannot be started once stopped");
            default:
                throw new Error("Invalid WorkerState");
        }

        // Wait until the startTime is initialized by the worker.
        /**
         * 上面只是修改了worker的状态，下面这里是不断的轮训，woeker没创建好得的时候会startTimeInitialized.countDown();
         * 而且startTime是0，此时这里会阻塞在startTimeInitialized.await();等worker创建好
         * 等worker创建那边创建好了，他那边是这样的
         * 首先他会给startTime赋值，保证这个值不是0，
         * 其次他会执行startTimeInitialized.countDown();
         * 那么此时这里的startTimeInitialized.await();阻塞就会放行下去，然后while循环也会结束。此时就能保证，你在执行
         * start开始处理任务的时候，我的worker线程已经创建好了，那么就可以执行任务了,他做了一个简单的同步等待。
         * 不然我那边还美好呢，你这就开始跑了，必然出问题。
         */

        while (startTime == 0) {
            try {
                startTimeInitialized.await();
            } catch (InterruptedException ignore) {
                // Ignore - it will be ready very soon.
            }
        }
    }

    @Override
    public Set<Timeout> stop() {
        if (Thread.currentThread() == workerThread) {
            throw new IllegalStateException(
                    HashedWheelTimer.class.getSimpleName() +
                            ".stop() cannot be called from " +
                            TimerTask.class.getSimpleName());
        }

        if (!WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_STARTED, WORKER_STATE_SHUTDOWN)) {
            // workerState can be 0 or 2 at this moment - let it always be 2.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
                if (leak != null) {
                    boolean closed = leak.close(this);
                    assert closed;
                }
            }

            return Collections.emptySet();
        }

        try {
            boolean interrupted = false;
            while (workerThread.isAlive()) {
                workerThread.interrupt();
                try {
                    workerThread.join(100);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            INSTANCE_COUNTER.decrementAndGet();
            if (leak != null) {
                boolean closed = leak.close(this);
                assert closed;
            }
        }
        return worker.unprocessedTimeouts();
    }

    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        checkNotNull(task, "task");
        checkNotNull(unit, "unit");

        // 添加任务之后，先加一，统计当前有多少个等待执行的任务
        long pendingTimeoutsCount = pendingTimeouts.incrementAndGet();

        // 如果超了设置的一个时间轮上的最大任务数，maxPendingTimeouts默认是-1表示不限制，你可以配置。
        if (maxPendingTimeouts > 0 && pendingTimeoutsCount > maxPendingTimeouts) {
            // 减一，再抛出异常，避免任务太多，这个任务被抛弃
            pendingTimeouts.decrementAndGet();
            throw new RejectedExecutionException("Number of pending timeouts ("
                + pendingTimeoutsCount + ") is greater than or equal to maximum allowed pending "
                + "timeouts (" + maxPendingTimeouts + ")");
        }

        // 启动worker线程，开始扫描轮盘，内部有同步的实现
        start();

        // Add the timeout to the timeout queue which will be processed on the next tick.
        // During processing all the queued HashedWheelTimeouts will be added to the correct HashedWheelBucket.
        // 计算出任务执行的时间，就是当前时间加上这个任务设置的延迟时间，减去这个任务创建的时间，就是基于这个任务创建的时间之后多久开始执行
        // startTime是worker启动的时间，所以他都要减去，从你任务创建的时间开始计算，就是基于你任务创建的时间之后多久开始执行
        // 因为基于毫秒来看，这些时间还是很大的不能忽略。
        long deadline = System.nanoTime() + unit.toNanos(delay) - startTime;

        // Guard against overflow. 这就是那种其实已经超时了的时间，错过了，就永远不执行了
        // 超时指的就是，在我时间轮的worker还没创建好呢，你就提交了任务，这种不执行
        /**
         * 这里避免的是用户瞎jb设置了一个很大的延迟时间，比如long.max_value，此时你执行System.nanoTime() + unit.toNanos(delay) - startTime;
         * 返回的数会超出long的表达范围发生溢出，是个负数了，这时候就给他弄个合法的正数，那就最大得了
         */
        if (delay > 0 && deadline < 0) {
            deadline = Long.MAX_VALUE;
        }

        // 此时把你的任务封装好，这才是netty时间轮要处理的对象，只是基于你的一些参数构造了一下，给netty用
        HashedWheelTimeout timeout = new HashedWheelTimeout(this, task, deadline);
        // 把任务添加到时间轮的任务队列中，worker线程会处理这个任务,按照轮的思想
        // 而且Mpsc是一个无锁队列，高性能，他的全称是multiple producer single consumer queue
        // 多生产者单消费者队列，就是说，一个队列，多个线程往里面添加数据，一个线程从里面取数据，但是不加锁保证性能和并发安全，优秀的很
        // 因为你一个时间轮可能有多个线程在往里面提交任务。
        // 此时任务已经被放入队列，其余的逻辑就落到了worker线程如何消费这个队列了。他给任务和调度任务不在一起，因为当前这个任务可能不调度，
        // 他是先给到队列里面，然后做处理，然后放到时间轮上，然后执行
        timeouts.add(timeout);
        return timeout;
    }

    /**
     * Returns the number of pending timeouts of this {@link Timer}.
     */
    public long pendingTimeouts() {
        return pendingTimeouts.get();
    }

    private static void reportTooManyInstances() {
        if (logger.isErrorEnabled()) {
            String resourceType = simpleClassName(HashedWheelTimer.class);
            logger.error("You are creating too many " + resourceType + " instances. " +
                    resourceType + " is a shared resource that must be reused across the JVM, " +
                    "so that only a few instances are created.");
        }
    }

    private final class Worker implements Runnable {
        private final Set<Timeout> unprocessedTimeouts = new HashSet<Timeout>();

        private long tick;

        @Override
        public void run() {
            // Initialize the startTime.
            startTime = System.nanoTime();
            if (startTime == 0) {
                // We use 0 as an indicator for the uninitialized value here, so make sure it's not 0 when initialized.
                startTime = 1;
            }

            // Notify the other threads waiting for the initialization at start().
            startTimeInitialized.countDown();

            do {
                /**
                 * 他是整点运行的，比如你设置的是100ms，拿他就是100ms 200ms这种执行。所以这里就是当他执行完了还不到整点
                 * 那他就睡眠阻塞，避免空转
                 * 这个方法的逻辑就是计算当前时间距离下一次执行格子的整点时间，还有多久，如果还有时间就睡眠。返回的是个正数
                 * 如果到了或者超了就立刻返回(是个负数)，不睡了。
                 */
                final long deadline = waitForNextTick();
                if (deadline > 0) {
                    /**
                     * tick就是你当前走到的时间轮子上的格数，第几个格子
                     * mask就是时间轮的格子数减去1，而格子数是2的n次方，所以tick & mask = tick % wheel.length -1
                     * 而和2的n次方-1做与运算，等于和这个2的n次方数做取余运算。所以这里其实就是再算当前的这个格子，位于轮子上的
                     * 哪个位置，他用的是取摸运算。
                     */
                    int idx = (int) (tick & mask);
                    // 处理那些取消的任务，任务可以取消的哈
                    processCancelledTasks();
                    // 处理要执行的格子上面的任务链表
                    HashedWheelBucket bucket = wheel[idx];
                    // 之前我们吧任务放在了队列中，这里我们要取出来队列任务，放到链表中，放入轮子里面了，执行这个轮子。这里是轮的重心逻辑
                    // 注意这里只是放入轮子，从队列取出来放入轮子，不是真正执行，只是放入轮子而已
                    transferTimeoutsToBuckets();
                    // 开始执行任务
                    bucket.expireTimeouts(deadline);
                    // 格子+1，往下转动
                    tick++;
                }
            } while (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_STARTED);

            // Fill the unprocessedTimeouts so we can return them from stop() method.
            for (HashedWheelBucket bucket: wheel) {
                bucket.clearTimeouts(unprocessedTimeouts);
            }
            for (;;) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    break;
                }
                if (!timeout.isCancelled()) {
                    unprocessedTimeouts.add(timeout);
                }
            }
            processCancelledTasks();
        }

        private void transferTimeoutsToBuckets() {
            // transfer only max. 100000 timeouts per tick to prevent a thread to stale the workerThread when it just
            // adds new timeouts in a loop. 循环十万次，其实就是从当前这个格子上取出十万个任务。注意这个数字固定了，所以他不是一次取完，
            // 因为你完全可以放十万个更多的。这里他不是一次取完，不然消耗太大了。他分批了。
            for (int i = 0; i < 100000; i++) {
                // 获取下一个任务
                HashedWheelTimeout timeout = timeouts.poll();
                // 为空说明处理完了，直接退出
                if (timeout == null) {
                    // all processed
                    break;
                }
                // 如果这个任务被取消了，跳过
                if (timeout.state() == HashedWheelTimeout.ST_CANCELLED) {
                    // Was cancelled in the meantime.
                    continue;
                }

                // 下面就是计算这个任务该放到哪个格子上，包括他是哪个轮次的，因为他可能转一圈才执行或者转几圈才执行
                long calculated = timeout.deadline / tickDuration;
                timeout.remainingRounds = (calculated - tick) / wheel.length;

                // 确认下一轮的任务也不会被丢掉，他确认你下一轮或者下几轮的不在本次执行，放入下一回
                // tick大于calculated的时候说明过期了，当任务很多，并且延迟很短的时候，没来得及从队列取出来就过期了
                // 因为每次都取10万个，可能好几次都取不到，但是tick还在自增，所以此时他可能已经过期了
                // 此时他就再也回不去执行哪个任务的tick了，所以他选择把你这个过期任务放到当前的格子里面了
                // 即便你可能属于第3个tick，但是加入此时到了第四个tick，那我会把你放到这里执行，虽然已经超了
                final long ticks = Math.max(calculated, tick); // Ensure we don't schedule for past.
                int stopIndex = (int) (ticks & mask);

                HashedWheelBucket bucket = wheel[stopIndex];
                bucket.addTimeout(timeout);
            }
        }

        private void processCancelledTasks() {
            for (;;) {
                HashedWheelTimeout timeout = cancelledTimeouts.poll();
                if (timeout == null) {
                    // all processed
                    break;
                }
                try {
                    timeout.remove();
                } catch (Throwable t) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("An exception was thrown while process a cancellation task", t);
                    }
                }
            }
        }

        /**
         * calculate goal nanoTime from startTime and current tick number,
         * then wait until that goal has been reached.
         * @return Long.MIN_VALUE if received a shutdown request,
         * current time otherwise (with Long.MIN_VALUE changed by +1)
         */
        private long waitForNextTick() {
            // tickDuration是我设置的我的设置的轮子上的格子代表的时间长度，这里计算的就是下一个格子执行的时间
            long deadline = tickDuration * (tick + 1);

            for (;;) {
                /**
                 * startTime是我们worker启动的时间
                 * 而任务提交进去那一刻开始人家就开始算这个任务的执行延迟时间了，这里不管你线程启动的消耗，所以要抛去这个线程启动的消耗
                 * 当前时间减去启动时间，就是我这个任务从提交到运行到这里的时间
                 */
                final long currentTime = System.nanoTime() - startTime;
                /**
                 * deadline是我这个任务要执行的时间，也就是我提交的时候指定的哪个延迟时间
                 * 而注意此时已经各种消耗线程创建，代码运行到这里已经消耗了时间了。经过消耗之后这个任务执行的延迟时间
                 * 我们上面已经计算出来为currentTime，那其实我任务还剩的执行时间就是deadline - currentTime
                 * 这个时间就是我要执行我的延迟任务还剩的时间，这个时间到现在还是纳秒。所以他除以了1000000变为了毫秒
                 * 那么为什么要+999999，因为他怕你这个时间太短，不然下面睡的时间太短了，资源消耗
                 * 因为这里要除1000000，而根据乘法分配律，你这里其实等于多加了1ms这个开销是可以接受的
                 * 这里我举个例子吧：
                 HashedWheelTimer timer = new HashedWheelTimer(1, TimeUnit.SECONDS,20);
                 timer.newTimeout(new TimerTask(){
                 @Override
                 public void run(Timeout timeout) {
                    System.out.println(" timeout ");
                 }
                }, 2, TimeUnit.SECONDS);
                 上面我创建了一个时间轮盘，其长度为20，但是他会被初始化为向上的2的n次方数，也就是32 我指定的每个格子是1秒。也就是我的
                 worker线程会每隔着一秒执行一次下一个格子
                 然后我提交了一个延迟2秒的任务，那么我提交任务的时候是纳秒的，而worker线程启动后开始执行，
                 那么到了这里long deadline = tickDuration * (tick + 1); 此时tickDuration就是1，tick是0(第一次走，第一个格子)
                 此时我的任务的格子就是1 * 0 + 1 = 1秒，这个没毛病，我们就是每个格子就是1秒跑一下
                 然后执行long currentTime = System.nanoTime() - startTime; startTime是线程启动的时间，用当前时间减去线程启动的消耗
                 时间，也就是算出来线程启动那个时间的时间。
                 然后我们来到这里deadline就是1秒 1-当前时间+999999  / 1000000  = 0.999999秒，其实也就是个1
                 此时就算出了距离执行下一个格子还剩多久。注意此时还没开始处理任务呢，只是算一下啥时候执行下一个格子。
                 他的逻辑就是通过每个格子的时间长度，然后当前时间减去前面的开销，看看这个时间长度还剩多久。而且加了一个0。1ms的偏移量避免太小了
                 因为他算出这个还剩多久这个剩的时间他要sleep，你要是太小了这个sleep的时间太短了，资源消耗,当你还剩很多的时候这个偏移量其实也起不到啥作用
                 杯水车薪罢了。所以你能看到他这个又是考虑启动开销，又是考虑偏移量，所以我们知道，netty的时间轮不准，不是那么的无比精确
                 *
                 */
                long sleepTimeMs = (deadline - currentTime + 999999) / 1000000;

                /**
                 * 或者是你启动消耗很大，此时减去之后是负数，那么这个任务已经过期了。因为你这时候启动了一个格子还大的长度，所以这时候这个格子里面
                 * 的任务就要立即执行，不用睡了。所以这里要立刻返回。
                 * 而且还能有负数或者0有一种可能，因为可能上一个任务太长了，超过了一个格子的时间，再次循环到这一轮的时候，就会发生负数，
                 * ，因为你要注意一点我们这个方法的功能是计算当前距离下一个格子执行的整点时间还有多久。执行完任务就要计算一次。如果距离下一个
                 * 格子运行时间还有距离，那就睡眠，所以要+一个999999，就怕你太小了，万一小的比1ns还小，那你还sleep个毛线，所以加了一个poch
                 * 而且睡眠方法最小还是毫秒，这个必须加一下偏移一把
                 * 那么他就会直接返回，不睡眠了。
                 */
                if (sleepTimeMs <= 0) {
                    if (currentTime == Long.MIN_VALUE) {
                        return -Long.MAX_VALUE;
                    } else {
                        return currentTime;
                    }
                }

                // Check if we run on windows, as if thats the case we will need
                // to round the sleepTime as workaround for a bug that only affect
                // the JVM if it runs on windows.
                //
                // See https://github.com/netty/netty/issues/356
                if (PlatformDependent.isWindows()) {
                    sleepTimeMs = sleepTimeMs / 10 * 10;
                    if (sleepTimeMs == 0) {
                        sleepTimeMs = 1;
                    }
                }

                try {
                    // 睡眠剩余时间，
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException ignored) {
                    if (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_SHUTDOWN) {
                        return Long.MIN_VALUE;
                    }
                }
            }
        }

        public Set<Timeout> unprocessedTimeouts() {
            return Collections.unmodifiableSet(unprocessedTimeouts);
        }
    }

    private static final class HashedWheelTimeout implements Timeout, Runnable {

        private static final int ST_INIT = 0;
        private static final int ST_CANCELLED = 1;
        private static final int ST_EXPIRED = 2;
        private static final AtomicIntegerFieldUpdater<HashedWheelTimeout> STATE_UPDATER =
                AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimeout.class, "state");

        private final HashedWheelTimer timer;
        private final TimerTask task;
        private final long deadline;

        @SuppressWarnings({"unused", "FieldMayBeFinal", "RedundantFieldInitialization" })
        private volatile int state = ST_INIT;

        // remainingRounds will be calculated and set by Worker.transferTimeoutsToBuckets() before the
        // HashedWheelTimeout will be added to the correct HashedWheelBucket.
        long remainingRounds;

        // This will be used to chain timeouts in HashedWheelTimerBucket via a double-linked-list.
        // As only the workerThread will act on it there is no need for synchronization / volatile.
        HashedWheelTimeout next;
        HashedWheelTimeout prev;

        // The bucket to which the timeout was added
        HashedWheelBucket bucket;

        HashedWheelTimeout(HashedWheelTimer timer, TimerTask task, long deadline) {
            this.timer = timer;
            this.task = task;
            this.deadline = deadline;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        public boolean cancel() {
            // only update the state it will be removed from HashedWheelBucket on next tick.
            if (!compareAndSetState(ST_INIT, ST_CANCELLED)) {
                return false;
            }
            // If a task should be canceled we put this to another queue which will be processed on each tick.
            // So this means that we will have a GC latency of max. 1 tick duration which is good enough. This way
            // we can make again use of our MpscLinkedQueue and so minimize the locking / overhead as much as possible.
            timer.cancelledTimeouts.add(this);
            return true;
        }

        void remove() {
            HashedWheelBucket bucket = this.bucket;
            if (bucket != null) {
                bucket.remove(this);
            } else {
                timer.pendingTimeouts.decrementAndGet();
            }
        }

        public boolean compareAndSetState(int expected, int state) {
            return STATE_UPDATER.compareAndSet(this, expected, state);
        }

        public int state() {
            return state;
        }

        @Override
        public boolean isCancelled() {
            return state() == ST_CANCELLED;
        }

        @Override
        public boolean isExpired() {
            return state() == ST_EXPIRED;
        }

        public void expire() {
            if (!compareAndSetState(ST_INIT, ST_EXPIRED)) {
                return;
            }

            try {
                timer.taskExecutor.execute(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown while submit " + TimerTask.class.getSimpleName()
                            + " for execution.", t);
                }
            }
        }

        @Override
        public void run() {
            try {
                task.run(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown by " + TimerTask.class.getSimpleName() + '.', t);
                }
            }
        }

        @Override
        public String toString() {
            final long currentTime = System.nanoTime();
            long remaining = deadline - currentTime + timer.startTime;

            StringBuilder buf = new StringBuilder(192)
               .append(simpleClassName(this))
               .append('(')
               .append("deadline: ");
            if (remaining > 0) {
                buf.append(remaining)
                   .append(" ns later");
            } else if (remaining < 0) {
                buf.append(-remaining)
                   .append(" ns ago");
            } else {
                buf.append("now");
            }

            if (isCancelled()) {
                buf.append(", cancelled");
            }

            return buf.append(", task: ")
                      .append(task())
                      .append(')')
                      .toString();
        }
    }

    /**
     * Bucket that stores HashedWheelTimeouts. These are stored in a linked-list like datastructure to allow easy
     * removal of HashedWheelTimeouts in the middle. Also the HashedWheelTimeout act as nodes themself and so no
     * extra object creation is needed.
     */
    private static final class HashedWheelBucket {
        // Used for the linked-list datastructure
        private HashedWheelTimeout head;
        private HashedWheelTimeout tail;

        /**
         * Add {@link HashedWheelTimeout} to this bucket.
         */
        public void addTimeout(HashedWheelTimeout timeout) {
            assert timeout.bucket == null;
            timeout.bucket = this;
            if (head == null) {
                head = tail = timeout;
            } else {
                tail.next = timeout;
                timeout.prev = tail;
                tail = timeout;
            }
        }

        /**
         * Expire all {@link HashedWheelTimeout}s for the given {@code deadline}.
         * 调度延迟任务，注意如果任务超时了，还是会执行，所以netty的时间轮任务不准
         */
        public void expireTimeouts(long deadline) {
            HashedWheelTimeout timeout = head;

            // process all timeouts
            while (timeout != null) {
                HashedWheelTimeout next = timeout.next;
                // 只要你的轮次满足，他就会进去
                if (timeout.remainingRounds <= 0) {
                    next = remove(timeout);
                    // 如果当前任务应该执行的的执行时间小于当前时间，也就是他超时了，其实也会执行，所以他是小于等于都会执行，
                    // 等于就是正好，小于就是超了，大于就是还没到呢
                    if (timeout.deadline <= deadline) {
                        // 任务开始run
                        timeout.expire();
                    } else {
                        // The timeout was placed into a wrong slot. This should never happen.
                        throw new IllegalStateException(String.format(
                                "timeout.deadline (%d) > deadline (%d)", timeout.deadline, deadline));
                    }
                } else if (timeout.isCancelled()) {
                    next = remove(timeout);
                } else {
                    timeout.remainingRounds --;
                }
                timeout = next;
            }
        }

        public HashedWheelTimeout remove(HashedWheelTimeout timeout) {
            HashedWheelTimeout next = timeout.next;
            // remove timeout that was either processed or cancelled by updating the linked-list
            if (timeout.prev != null) {
                timeout.prev.next = next;
            }
            if (timeout.next != null) {
                timeout.next.prev = timeout.prev;
            }

            if (timeout == head) {
                // if timeout is also the tail we need to adjust the entry too
                if (timeout == tail) {
                    tail = null;
                    head = null;
                } else {
                    head = next;
                }
            } else if (timeout == tail) {
                // if the timeout is the tail modify the tail to be the prev node.
                tail = timeout.prev;
            }
            // null out prev, next and bucket to allow for GC.
            timeout.prev = null;
            timeout.next = null;
            timeout.bucket = null;
            timeout.timer.pendingTimeouts.decrementAndGet();
            return next;
        }

        /**
         * Clear this bucket and return all not expired / cancelled {@link Timeout}s.
         */
        public void clearTimeouts(Set<Timeout> set) {
            for (;;) {
                HashedWheelTimeout timeout = pollTimeout();
                if (timeout == null) {
                    return;
                }
                if (timeout.isExpired() || timeout.isCancelled()) {
                    continue;
                }
                set.add(timeout);
            }
        }

        private HashedWheelTimeout pollTimeout() {
            HashedWheelTimeout head = this.head;
            if (head == null) {
                return null;
            }
            HashedWheelTimeout next = head.next;
            if (next == null) {
                tail = this.head =  null;
            } else {
                this.head = next;
                next.prev = null;
            }

            // null out prev and next to allow for GC.
            head.next = null;
            head.prev = null;
            head.bucket = null;
            return head;
        }
    }
}
