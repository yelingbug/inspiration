/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.cluster;

import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.support.FailoverCluster;

/**
 * Cluster. (SPI, Singleton, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Computer_cluster">Cluster</a>
 * <a href="http://en.wikipedia.org/wiki/Fault-tolerant_system">Fault-Tolerant</a>
 *
 */
@SPI(FailoverCluster.NAME)//解释一些SPI和Adaptive之间的关系，如果这里有一个值，ExtensionLoader#cachedDefaultName就等于这个值，用于缺省。
public interface Cluster {

    /**
     * Merge the directory invokers to a virtual invoker.
     *
     * @param <T>
     * @param directory
     * @return cluster invoker
     * @throws RpcException
     */
    @Adaptive//ExtensionLoader#getAdaptiveExtension()时，如果没有发现类级别的Adaptive注解，就尝试从接口的方法中找Adaptive注解，并且利用createAdaptiveExtensionClass
    //方法结合方法的参数等条件动态的创建具体接口的实现类，创建实现类的时候依据Adaptive的参数，以及方法的参数，cachedDefaultName等找到对应的extension，在从容器中
    // 找到extension对应的接口实现。这里的extension其实就是FailoverCluster.NAME
    <T> Invoker<T> join(Directory<T> directory) throws RpcException;

}