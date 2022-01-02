/*
 * Copyright 2020 The Netty Project
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
package io.netty.buffer;

/**
 * Expose metrics for an SizeClasses.
 */
public interface SizeClassesMetric {

    /**
     * Computes size from lookup table according to sizeIdx.
     * 根据数组索引值获取[size]大小(根据索引直接从sizeIdx2sizeTab[]数组读取).
     *
     * commented by Yelin.G on 2021.11.13
     *
     * @return size
     */
    int sizeIdx2size(int sizeIdx);

    /**
     * Computes size according to sizeIdx.
     *
     * @return size
     */
    int sizeIdx2sizeCompute(int sizeIdx);

    /**
     * Computes size from lookup table according to pageIdx.
     *
     * @return size which is multiples of pageSize.
     */
    long pageIdx2size(int pageIdx);

    /**
     * Computes size according to pageIdx.
     *
     * @return size which is multiples of pageSize
     */
    long pageIdx2sizeCompute(int pageIdx);

    /**
     * Normalizes request size up to the nearest size class.
     * 将请求的大小规格化为最接近的值，比如申请大小为14，则返回数组索引值为0，后续通过该数值
     * 索引就能得到对应的size值。
     *
     * commented by Yelin.G on 2021.11.13
     *
     * @param size request size
     *
     * @return sizeIdx of the size class
     */
    int size2SizeIdx(int size);

    /**
     * Normalizes request size up to the nearest pageSize class.
     *
     * @param pages multiples of pageSizes
     *
     * @return pageIdx of the pageSize class
     */
    int pages2pageIdx(int pages);

    /**
     * Normalizes request size down to the nearest pageSize class.
     * 对页的数量规格化计算获取最接近的pages容量大小的索引，比如pages=5，表示需要分配
     * 5pages大小的内存快，因此会从SizeClass数组中查找对应的索引值。
     *
     * commented by Yelin.G on 2021.11.13
     *
     * @param pages multiples of pageSizes
     *
     * @return pageIdx of the pageSize class
     */
    int pages2pageIdxFloor(int pages);

    /**
     * Normalizes usable size that would result from allocating an object with the
     * specified size and alignment.
     *
     * @param size request size
     *
     * @return normalized size
     */
    int normalizeSize(int size);
}
