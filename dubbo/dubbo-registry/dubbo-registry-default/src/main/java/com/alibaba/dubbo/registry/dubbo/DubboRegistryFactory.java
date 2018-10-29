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
package com.alibaba.dubbo.registry.dubbo;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.bytecode.Wrapper;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.registry.RegistryService;
import com.alibaba.dubbo.registry.integration.RegistryDirectory;
import com.alibaba.dubbo.registry.support.AbstractRegistryFactory;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.cluster.Cluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * DubboRegistryFactory
 *
 */
public class DubboRegistryFactory extends AbstractRegistryFactory {

    private Protocol protocol;
    private ProxyFactory proxyFactory;
    private Cluster cluster;

    private static URL getRegistryURL(URL url) {
        return url.setPath(RegistryService.class.getName())
                .removeParameter(Constants.EXPORT_KEY).removeParameter(Constants.REFER_KEY)
                .addParameter(Constants.INTERFACE_KEY, RegistryService.class.getName())
                .addParameter(Constants.CLUSTER_STICKY_KEY, "true")
                .addParameter(Constants.LAZY_CONNECT_KEY, "true")
                .addParameter(Constants.RECONNECT_KEY, "false")
                .addParameterIfAbsent(Constants.TIMEOUT_KEY, "10000")
                .addParameterIfAbsent(Constants.CALLBACK_INSTANCES_LIMIT_KEY, "10000")
                .addParameterIfAbsent(Constants.CONNECT_TIMEOUT_KEY, "10000")
                .addParameter(Constants.METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(Wrapper.getWrapper(RegistryService.class).getDeclaredMethodNames())), ","))
                //.addParameter(Constants.STUB_KEY, RegistryServiceStub.class.getName())
                //.addParameter(Constants.STUB_EVENT_KEY, Boolean.TRUE.toString()) //for event dispatch
                //.addParameter(Constants.ON_DISCONNECT_KEY, "disconnect")
                .addParameter("subscribe.1.callback", "true")//基于客户端已经创建的通道创建回调服务端
                .addParameter("unsubscribe.1.callback", "false");
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    public void setCluster(Cluster cluster) {
        this.cluster = cluster;
    }

    public Registry createRegistry(URL url) {
        url = getRegistryURL(url);
        List<URL> urls = new ArrayList<URL>();
        urls.add(url.removeParameter(Constants.BACKUP_KEY));
        String backup = url.getParameter(Constants.BACKUP_KEY);
        if (backup != null && backup.length() > 0) {
            String[] addresses = Constants.COMMA_SPLIT_PATTERN.split(backup);
            for (String address : addresses) {
                urls.add(url.setAddress(address));
            }
        }
        RegistryDirectory<RegistryService> directory = new RegistryDirectory<RegistryService>(RegistryService.class, url.addParameter(Constants.INTERFACE_KEY, RegistryService.class.getName()).addParameterAndEncoded(Constants.REFER_KEY, url.toParameterString()));
        Invoker<RegistryService> registryInvoker = cluster.join(directory);
        RegistryService registryService = proxyFactory.getProxy(registryInvoker);//创建基于registryInvoker的动态代理(RegistryService接口的动态代理)，在代理中增加了负载均衡的策略，也就说当调用registryService具体的方法时，
        //附加前置的负载均衡策略。
        DubboRegistry registry = new DubboRegistry(registryInvoker, registryService);
        directory.setRegistry(registry);
        directory.setProtocol(protocol);
        directory.notify(urls);
        directory.subscribe(new URL(Constants.CONSUMER_PROTOCOL, NetUtils.getLocalHost(), 0, RegistryService.class.getName(), url.getParameters()));//实际上是调用RegistryDirectory中的register(Registry类型)，
        //register(Registry类型)是DubboRegistry->FailbackRegistry类型，进而调用FailbackRegistry中的subscribe到DubboRegistry#doSubscribe，即DubboRegistry中的registryService，从上面代码看出registryService就是
        //RegistryService registryService = proxyFactory.getProxy(registryInvoker)创建的动态代理来的。
        return registry;
    }
}