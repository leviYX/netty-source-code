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
package io.netty.channel;

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    static final int DEFAULT_MINIMUM = 64;
    // Use an initial value that is bigger than the common MTU of 1500
    static final int DEFAULT_INITIAL = 2048;
    static final int DEFAULT_MAXIMUM = 65536;

    /**
     * 扩容的索引步长，每次加4，比如说，我当前的bytebuf为16字节，通过一次接收，我发现太小了，我要扩容一次，为下次做准备
     * 于是我的扩容步长为4，我当前16在SIZE_TABLE位于第一个格子，也即是下标为0，下一次扩容的步长为4
     * 下标为1的是32 下标为2的是48 下标为3的是64 下标为4的是80 下标为5的是96 所以我扩容就是0+4=4 数字为80，所以我的下次扩为80
     *
     * 缩容步长为1，每次拿前一个格子的数字作为缩容大小
     */
    private static final int INDEX_INCREMENT = 4;
    private static final int INDEX_DECREMENT = 1;

    private static final int[] SIZE_TABLE;

    // 在这里处理bytebuf的预测大小，也就是下一次大小
    static {
        // 扩容池，存储能扩容的大小范围都有哪些
        List<Integer> sizeTable = new ArrayList<Integer>();
        // 存储内容为16 ~ 512 每次步长16，这是一些小容量的内容，所以你能看到bytebuf最小也得是16字节
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        // Suppress a warning since i becomes negative when an integer overflow happens
        // 这里放入大的数，一直乘以2，直到integer溢出为止，此时超过int最大值，i>0不再满足，因为变为负数了
        // 所以这里初始化了一堆很大的数，最后直达int最大值，int最大值是2147483647，能容纳2147483647个字节，也就是能容纳2g
        // 这里可以看出来netty每次接收数据的bytebuf大小是2g上限，而且这只是理论值，实际上不会这么大，socket缓冲区也有限制
        for (int i = 512; i > 0; i <<= 1) {
            sizeTable.add(i);
        }

        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            // 然后把这个计算出来的大小集合赋值给SIZE_TABLE保存起来，也就是当我扩容的时候，每次的大小只能是SIZE_TABLE里面的数，不能是其他的
            // 所以所谓扩容或者缩容就是从这个集合中找一个数字，这个数字就是大小
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    /**
     * 当我扩容的时候，根据这个索引找到对应的值,比如我想扩容为16，那就是第一个数，返回索引为0。根据容量找索引
     * @param size
     * @return
     */
    private static int getSizeTableIndex(final int size) {
        // 找的时候用的是二分法，如果正好有这个数返回，没有这个数就返回临近的
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private final class HandleImpl extends MaxMessageHandle {
        private final int minIndex;
        private final int maxIndex;
        private final int minCapacity;
        private final int maxCapacity;
        private int index;
        private int nextReceiveBufferSize;
        private boolean decreaseNow;

        HandleImpl(int minIndex, int maxIndex, int initialIndex, int minCapacity, int maxCapacity) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            index = initialIndex;
            nextReceiveBufferSize = max(SIZE_TABLE[index], minCapacity);
            this.minCapacity = minCapacity;
            this.maxCapacity = maxCapacity;
        }

        /**
         * 当前这一次读取的大小数据量，然后根据本次的读取来计算是不是要扩容
         */
        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            // bytes就是我们当前读的大小，如果当前读的和我们企图读的大小相等，企图读就是我们当前bytebufbuf的容量
            // 也就是我们读的和当前容量一样的，意思就是我们读满了，那有可能不够，此时要扩容，否则就不变
            if (bytes == attemptedBytesRead()) {
                record(bytes);
            }
            // 把扩容或者没变的数据存起来
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() {
            // 返回的是下一次的大小，根据本次读取的能力来动态调整下一次的大小
            // 最终在io.netty.channel.AdaptiveRecvByteBufAllocator的静态代码块中会对这个值做处理
            return nextReceiveBufferSize;
        }

        /**
         * actualReadBytes为当前实际读到的数据量
         * @param actualReadBytes
         */
        private void record(int actualReadBytes) {

            /**
             * INDEX_DECREMENT = 1
             * 缩容判断，当你当前读的数据小于现在位置的大小的前一位，index - 1就是当前位置的前一位。
             * 我梳理一下，我们存容量的那个集合为下标为0的是16 下标为1的是32 下标为2的是48 下标为3的是64 下标为4的是80 下标为5的是96
             * 第一次的时候我们的bytebuf的容量为16，也就是下标为index=0 那么此时 0-1就是-1，而max(0,-1)=0.我们保证还是最小值就是16
             * 而我们假如第一次读的小于16，此时就进去执行if (decreaseNow)，而decreaseNow初始为false，所以不会扩容，而是把decreaseNow = true;
             * 那么下次进来，你要是还是读的小于了前一位的其实还是16，那么就会触发缩容了，因为上次已经把decreaseNow = true;所以这次进来就会缩容
             *
             * 假如经过n次处理可能已经扩容过了，此时的bytebuf大小为64，而且扩容会把decreaseNow = false;那么假如我们此时读的大小比64的前一位的
             * 48的还小，那此时你decreaseNow之前扩容就是false，那么这次还不会缩容，只会把decreaseNow弄为true，那下次要是再小于48，那就真
             * 会缩容了。他的处理逻辑是扩容激进一点，缩容保守一点，而且扩一次就走4个步长，缩才1个。
             */
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {
                if (decreaseNow) {
                    index = max(index - INDEX_DECREMENT, minIndex);
                    // 计算下一次的容量保存起来
                    nextReceiveBufferSize = max(SIZE_TABLE[index], minCapacity);
                    decreaseNow = false;
                } else {
                    // 第一次只是打标，下一次进来才会走上面的缩容，所以他是两次不满足条件才会缩容。比较激进，不是立即缩容
                    decreaseNow = true;
                }
            }
            // 扩容，当你当前读到的大于预测的下一次的了，立马触发扩容
            else if (actualReadBytes >= nextReceiveBufferSize) {
                index = min(index + INDEX_INCREMENT, maxIndex);
                // 计算下一次的容量保存起来
                nextReceiveBufferSize = min(SIZE_TABLE[index], maxCapacity);
                // 扩容会给你弄成false
                decreaseNow = false;
            }
        }

        @Override
        public void readComplete() {
            record(totalBytesRead());
        }
    }

    private final int minIndex;
    private final int maxIndex;
    private final int initialIndex;
    private final int minCapacity;
    private final int maxCapacity;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        int initialIndex = getSizeTableIndex(initial);
        if (SIZE_TABLE[initialIndex] > initial) {
            this.initialIndex = initialIndex - 1;
        } else {
            this.initialIndex = initialIndex;
        }
        this.minCapacity = minimum;
        this.maxCapacity = maximum;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initialIndex, minCapacity, maxCapacity);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
