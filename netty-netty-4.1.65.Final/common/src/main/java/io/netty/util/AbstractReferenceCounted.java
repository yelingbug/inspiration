/*
 * Copyright 2013 The Netty Project
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

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.util.internal.ReferenceCountUpdater;

/**
 * Abstract base class for classes wants to implement {@link ReferenceCounted}.
 */
public abstract class AbstractReferenceCounted implements ReferenceCounted {
    private static final long REFCNT_FIELD_OFFSET =
            ReferenceCountUpdater.getUnsafeOffset(AbstractReferenceCounted.class, "refCnt");
    /**
     * 为什么使用{@link AtomicIntegerFieldUpdater}而不是{@link java.util.concurrent.atomic.AtomicInteger}？
     * 1, {@link java.util.concurrent.atomic.AtomicInteger}支持原子更新，但是在多线程条件下如果要保证只有一个线程可以修改，
     * 那就要锁控制，有没有比锁控制更好的办法？用{@link AtomicIntegerFieldUpdater}，本质上通过CAS的方式。
     * 2，{@link AtomicIntegerFieldUpdater}使用有条件，修改的变量必须是volatile，并且不是static和final的。
     * 3，另外，{@link AtomicIntegerFieldUpdater}可以节约内存：
     *   在AtomicInteger成员变量只有一个int value，似乎好像并没有多出内存，但是我们的AtomicInteger是一个对象，一个对象的正确计算应该是 对象头 + 数据大小，在64位机器上AtomicInteger对象占用内存如下：
     *   关闭指针压缩：16(对象头)+4(实例数据)=20不是8的倍数，因此需要对齐填充 16+4+4(padding)=24
     *   开启指针压缩（-XX:+UseCompressedOop): 12+4=16已经是8的倍数了，不需要再padding。
     *
     *   由于我们的AtomicInteger是一个对象，还需要被引用，那么真实的占用为：
     *   关闭指针压缩：24 + 8 = 32
     *   开启指针压缩: 16 + 4 = 20
     *
     *   而fieldUpdater是staic final类型并不会占用我们对象的内存，所以使用filedUpdater的话可以近似认为只用了4字节，这个再未关闭指针压缩的情况下节约了7倍，关闭的情况下节约了4倍，这个在少量对象的情况下可能不明显，当我们对象有几十万，几百万，或者几千万的时候，节约的可能就是几十M,几百M,甚至几个G。
     *   比如在netty中的AbstractReferenceCountedByteBuf，熟悉netty的同学都知道netty是自己管理内存的，所有的ByteBuf都会继承AbstractReferenceCountedByteBuf，在netty中ByteBuf会被大量的创建，netty使用filedUpdater用于节约内存。
     * 4，其他诸如{@link java.util.concurrent.atomic.AtomicLongFieldUpdater}等都类似。
     *
     * commented by Yelin.G on 2021.10.17
     */
    private static final AtomicIntegerFieldUpdater<AbstractReferenceCounted> AIF_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractReferenceCounted.class, "refCnt");

    private static final ReferenceCountUpdater<AbstractReferenceCounted> updater =
            new ReferenceCountUpdater<AbstractReferenceCounted>() {
        @Override
        protected AtomicIntegerFieldUpdater<AbstractReferenceCounted> updater() {
            return AIF_UPDATER;
        }
        @Override
        protected long unsafeOffset() {
            return REFCNT_FIELD_OFFSET;
        }
    };

    // Value might not equal "real" reference count, all access should be via the updater
    @SuppressWarnings("unused")
    private volatile int refCnt = updater.initialValue();

    @Override
    public int refCnt() {
        return updater.refCnt(this);
    }

    /**
     * An unsafe operation intended for use by a subclass that sets the reference count of the buffer directly
     */
    protected final void setRefCnt(int refCnt) {
        updater.setRefCnt(this, refCnt);
    }

    @Override
    public ReferenceCounted retain() {
        return updater.retain(this);
    }

    @Override
    public ReferenceCounted retain(int increment) {
        return updater.retain(this, increment);
    }

    @Override
    public ReferenceCounted touch() {
        return touch(null);
    }

    @Override
    public boolean release() {
        return handleRelease(updater.release(this));
    }

    @Override
    public boolean release(int decrement) {
        return handleRelease(updater.release(this, decrement));
    }

    private boolean handleRelease(boolean result) {
        if (result) {
            deallocate();
        }
        return result;
    }

    /**
     * Called once {@link #refCnt()} is equals 0.
     */
    protected abstract void deallocate();
}
