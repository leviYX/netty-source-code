/*
 * Copyright 2016 The Netty Project
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

import io.netty.util.IntSupplier;

/**
 * Default select strategy.
 */
final class DefaultSelectStrategy implements SelectStrategy {
    static final SelectStrategy INSTANCE = new DefaultSelectStrategy();

    private DefaultSelectStrategy() { }

    @Override
    public int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception {
        /**
         * 是否存在普通任务(taskQueue and tailQueue) 如果存在，则返回selectNow()，否则返回select()
         * selectNow()执行的时候其实也会查看当前是不是有io事件，也就是selectionkey是不是有就绪的，
         * 所以有异步任务：>=0  执行selectNow()查看有几个就绪事件，所以至少是=0，=0就没有就绪io事件，有就是大于0
         * 没有异步任务：SelectStrategy.SELECT = -1
         */
        return hasTasks ? selectSupplier.get() : SelectStrategy.SELECT;
    }
}
