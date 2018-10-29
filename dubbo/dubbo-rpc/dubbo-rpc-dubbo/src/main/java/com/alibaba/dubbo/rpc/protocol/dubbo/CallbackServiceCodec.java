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
package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.bytecode.Wrapper;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.RpcInvocation;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * callback service helper
 */
class CallbackServiceCodec {
    private static final Logger logger = LoggerFactory.getLogger(CallbackServiceCodec.class);

    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
    private static final DubboProtocol protocol = DubboProtocol.getDubboProtocol();
    private static final byte CALLBACK_NONE = 0x0;
    private static final byte CALLBACK_CREATE = 0x1;
    private static final byte CALLBACK_DESTROY = 0x2;
    private static final String INV_ATT_CALLBACK_KEY = "sys_callback_arg-";

    private static byte isCallBack(URL url, String methodName, int argIndex) {
        // parameter callback rule: method-name.parameter-index(starting from 0).callback
        byte isCallback = CALLBACK_NONE;
        if (url != null) {
            String callback = url.getParameter(methodName + "." + argIndex + ".callback");//这里的一个例子就是DubboRegistryFactory.getRegistryURL()方法实现中的
            //addParameter("subscribe.1.callback", "true")及addParameter("unsubscribe.1.callback", "false")
            if (callback != null) {
                if (callback.equalsIgnoreCase("true")) {
                    isCallback = CALLBACK_CREATE;
                } else if (callback.equalsIgnoreCase("false")) {
                    isCallback = CALLBACK_DESTROY;
                }
            }
        }
        return isCallback;
    }

    /**
     * export or unexport callback service on client side
     *
     * @param channel
     * @param url
     * @param clazz
     * @param inst
     * @param export
     * @throws IOException
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String exportOrunexportCallbackService(Channel channel, URL url, Class clazz, Object inst, Boolean export) throws IOException {
        int instid = System.identityHashCode(inst);

        Map<String, String> params = new HashMap<String, String>(3);
        // no need to new client again
        params.put(Constants.IS_SERVER_KEY, Boolean.FALSE.toString());
        // mark it's a callback, for troubleshooting
        params.put(Constants.IS_CALLBACK_SERVICE, Boolean.TRUE.toString());
        String group = url.getParameter(Constants.GROUP_KEY);
        if (group != null && group.length() > 0) {
            params.put(Constants.GROUP_KEY, group);
        }
        // add method, for verifying against method, automatic fallback (see dubbo protocol)
        params.put(Constants.METHODS_KEY, StringUtils.join(Wrapper.getWrapper(clazz).getDeclaredMethodNames(), ","));

        Map<String, String> tmpmap = new HashMap<String, String>(url.getParameters());
        tmpmap.putAll(params);
        tmpmap.remove(Constants.VERSION_KEY);// doesn't need to distinguish version for callback
        tmpmap.put(Constants.INTERFACE_KEY, clazz.getName());
        /**
         * 值得注意的是，这里暴露回调参数实现服务的时候，用的端口是和消费端调用服务端的连接端口是一致的，换句话说，其实这是一个双向通道，如图：
         * 在消费端调用服务端接口的时候，比如callbackService.addListener()的时候，底层连接是这样的：consumer(c-ip:c-port)--->provider(s-ip:s-port)，这个时候
         * 消费端本地创建连接选择了一个端口，而在请求handler阶段走到这里的时候，需要对服务端暴露回调接口的服务，用的是相同的端口(看下面的代码
         * channel.getLocalAddress().getPort())，所以等到服务端回调这个服务的时候底层连接应该是这样的：consumer(c-ip:c-port)<----provider(s-ip:s-port)，结果
         * 其实就是一个双向通道consumer(c-ip:c-port)<--->provider(s-ip:s-port)
         */
        URL exporturl = new URL(DubboProtocol.NAME, channel.getLocalAddress().getAddress().getHostAddress(), channel.getLocalAddress().getPort(), clazz.getName() + "." + instid, tmpmap);

        // no need to generate multiple exporters for different channel in the same JVM, cache key cannot collide.
        String cacheKey = getClientSideCallbackServiceCacheKey(instid);
        String countkey = getClientSideCountKey(clazz.getName());
        if (export) {
            // one channel can have multiple callback instances, no need to re-export for different instance.
            if (!channel.hasAttribute(cacheKey)) {//对参数回调来说，其实就是在客户端export一个服务被服务端连接调用来主动通知，换句话说就是基于长连接生成反向代理
                if (!isInstancesOverLimit(channel, url, clazz.getName(), instid, false)) {
                    Invoker<?> invoker = proxyFactory.getInvoker(inst, clazz, exporturl);
                    // should destroy resource?
                    Exporter<?> exporter = protocol.export(invoker);
                    // this is used for tracing if instid has published service or not.
                    channel.setAttribute(cacheKey, exporter);
                    logger.info("export a callback service :" + exporturl + ", on " + channel + ", url is: " + url);
                    increaseInstanceCount(channel, countkey);
                }
            }
        } else {
            if (channel.hasAttribute(cacheKey)) {
                Exporter<?> exporter = (Exporter<?>) channel.getAttribute(cacheKey);
                exporter.unexport();
                channel.removeAttribute(cacheKey);
                decreaseInstanceCount(channel, countkey);
            }
        }
        return String.valueOf(instid);
    }

    /**
     * refer or destroy callback service on server side
     *
     * @param url
     */
    @SuppressWarnings("unchecked")
    private static Object referOrdestroyCallbackService(Channel channel, URL url, Class<?> clazz, Invocation inv, int instid, boolean isRefer) {
        Object proxy = null;
        String invokerCacheKey = getServerSideCallbackInvokerCacheKey(channel, clazz.getName(), instid);
        String proxyCacheKey = getServerSideCallbackServiceCacheKey(channel, clazz.getName(), instid);
        proxy = channel.getAttribute(proxyCacheKey);
        String countkey = getServerSideCountKey(channel, clazz.getName());
        if (isRefer) {
            if (proxy == null) {
                URL referurl = URL.valueOf("callback://" + url.getAddress() + "/" + clazz.getName() + "?" + Constants.INTERFACE_KEY + "=" + clazz.getName());
                referurl = referurl.addParametersIfAbsent(url.getParameters()).removeParameter(Constants.METHODS_KEY);
                if (!isInstancesOverLimit(channel, referurl, clazz.getName(), instid, true)) {
                    /**
                     * (接exportOrunexportCallbackService方法中的注释)所以也就不难理解，这里直接就用了消费端调用服务端方法请求的channel从服务端直接
                     * 向消费端回写数据)
                     */
                    @SuppressWarnings("rawtypes")
                    Invoker<?> invoker = new ChannelWrappedInvoker(clazz, channel, referurl, String.valueOf(instid));
                    proxy = proxyFactory.getProxy(invoker);
                    channel.setAttribute(proxyCacheKey, proxy);
                    channel.setAttribute(invokerCacheKey, invoker);
                    increaseInstanceCount(channel, countkey);

                    //convert error fail fast .
                    //ignore concurrent problem. 
                    Set<Invoker<?>> callbackInvokers = (Set<Invoker<?>>) channel.getAttribute(Constants.CHANNEL_CALLBACK_KEY);
                    if (callbackInvokers == null) {
                        callbackInvokers = new ConcurrentHashSet<Invoker<?>>(1);
                        callbackInvokers.add(invoker);
                        channel.setAttribute(Constants.CHANNEL_CALLBACK_KEY, callbackInvokers);
                    }
                    logger.info("method " + inv.getMethodName() + " include a callback service :" + invoker.getUrl() + ", a proxy :" + invoker + " has been created.");
                }
            }
        } else {
            if (proxy != null) {
                Invoker<?> invoker = (Invoker<?>) channel.getAttribute(invokerCacheKey);
                try {
                    Set<Invoker<?>> callbackInvokers = (Set<Invoker<?>>) channel.getAttribute(Constants.CHANNEL_CALLBACK_KEY);
                    if (callbackInvokers != null) {
                        callbackInvokers.remove(invoker);
                    }
                    invoker.destroy();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
                // cancel refer, directly remove from the map
                channel.removeAttribute(proxyCacheKey);
                channel.removeAttribute(invokerCacheKey);
                decreaseInstanceCount(channel, countkey);
            }
        }
        return proxy;
    }

    private static String getClientSideCallbackServiceCacheKey(int instid) {
        return Constants.CALLBACK_SERVICE_KEY + "." + instid;
    }

    private static String getServerSideCallbackServiceCacheKey(Channel channel, String interfaceClass, int instid) {
        return Constants.CALLBACK_SERVICE_PROXY_KEY + "." + System.identityHashCode(channel) + "." + interfaceClass + "." + instid;
    }

    private static String getServerSideCallbackInvokerCacheKey(Channel channel, String interfaceClass, int instid) {
        return getServerSideCallbackServiceCacheKey(channel, interfaceClass, instid) + "." + "invoker";
    }

    private static String getClientSideCountKey(String interfaceClass) {
        return Constants.CALLBACK_SERVICE_KEY + "." + interfaceClass + ".COUNT";
    }

    private static String getServerSideCountKey(Channel channel, String interfaceClass) {
        return Constants.CALLBACK_SERVICE_PROXY_KEY + "." + System.identityHashCode(channel) + "." + interfaceClass + ".COUNT";
    }

    private static boolean isInstancesOverLimit(Channel channel, URL url, String interfaceClass, int instid, boolean isServer) {
        Integer count = (Integer) channel.getAttribute(isServer ? getServerSideCountKey(channel, interfaceClass) : getClientSideCountKey(interfaceClass));
        int limit = url.getParameter(Constants.CALLBACK_INSTANCES_LIMIT_KEY, Constants.DEFAULT_CALLBACK_INSTANCES);
        if (count != null && count >= limit) {
            //client side error
            throw new IllegalStateException("interface " + interfaceClass + " `s callback instances num exceed providers limit :" + limit
                    + " ,current num: " + (count + 1) + ". The new callback service will not work !!! you can cancle the callback service which exported before. channel :" + channel);
        } else {
            return false;
        }
    }

    private static void increaseInstanceCount(Channel channel, String countkey) {
        try {
            //ignore concurrent problem?
            Integer count = (Integer) channel.getAttribute(countkey);
            if (count == null) {
                count = 1;
            } else {
                count++;
            }
            channel.setAttribute(countkey, count);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private static void decreaseInstanceCount(Channel channel, String countkey) {
        try {
            Integer count = (Integer) channel.getAttribute(countkey);
            if (count == null || count <= 0) {
                return;
            } else {
                count--;
            }
            channel.setAttribute(countkey, count);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    public static Object encodeInvocationArgument(Channel channel, RpcInvocation inv, int paraIndex) throws IOException {
        // get URL directly
        URL url = inv.getInvoker() == null ? null : inv.getInvoker().getUrl();
        byte callbackstatus = isCallBack(url, inv.getMethodName(), paraIndex);
        Object[] args = inv.getArguments();
        Class<?>[] pts = inv.getParameterTypes();
        switch (callbackstatus) {
            case CallbackServiceCodec.CALLBACK_NONE:
                return args[paraIndex];
            case CallbackServiceCodec.CALLBACK_CREATE:
                //消费端调用的时候，根据回调的参数类型(比如Listener接口实现)，在本端通过dubbo协议暴露一个针对Listener接口实现的服务，供服务端在回调的时候触发调用
                inv.setAttachment(INV_ATT_CALLBACK_KEY + paraIndex, exportOrunexportCallbackService(channel, url, pts[paraIndex], args[paraIndex], true));
                return null;
            case CallbackServiceCodec.CALLBACK_DESTROY:
                inv.setAttachment(INV_ATT_CALLBACK_KEY + paraIndex, exportOrunexportCallbackService(channel, url, pts[paraIndex], args[paraIndex], false));
                return null;
            default:
                return args[paraIndex];
        }
    }

    public static Object decodeInvocationArgument(Channel channel, RpcInvocation inv, Class<?>[] pts, int paraIndex, Object inObject) throws IOException {
        // if it's a callback, create proxy on client side, callback interface on client side can be invoked through channel
        // need get URL from channel and env when decode
        URL url = null;
        try {
            url = DubboProtocol.getDubboProtocol().getInvoker(channel, inv).getUrl();
        } catch (RemotingException e) {
            if (logger.isInfoEnabled()) {
                logger.info(e.getMessage(), e);
            }
            return inObject;
        }
        byte callbackstatus = isCallBack(url, inv.getMethodName(), paraIndex);
        switch (callbackstatus) {
            case CallbackServiceCodec.CALLBACK_NONE:
                return inObject;
            case CallbackServiceCodec.CALLBACK_CREATE:
                try {
                    //服务端拿到回调请求之后解码参数类型，并针对这个类型生成一个代理Invoker。当服务端回调这个参数接口的具体方法时，实际上就触发这个Invoker的调用，看
                    //Invoker里边的实现其实就是利用这个长连接Channel向消费端发送请求，因为消费端对这个接口的实现已经自动暴露了一个服务。

                    //解码后的参数实际上是一个基于invoker的代理proxy，比如说客户端调用的是subscribe(URL), RegistryDirectory)，这个RegistryDirectory是个listener，订阅请求
                    //服务端收到后是会根据订阅的内容回调notify方法把注册中心的URLs推送到客户端，所以说服务端收到的RegistryDirectory对象实际上是通过callback://客户端地址/RegistryDirectory.class
                    //地址连接到客户端暴露的服务的连接代理，即基于Invoker的代理proxy
                    return referOrdestroyCallbackService(channel, url, pts[paraIndex], inv, Integer.parseInt(inv.getAttachment(INV_ATT_CALLBACK_KEY + paraIndex)), true);
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                    throw new IOException(StringUtils.toString(e));
                }
            case CallbackServiceCodec.CALLBACK_DESTROY:
                try {
                    return referOrdestroyCallbackService(channel, url, pts[paraIndex], inv, Integer.parseInt(inv.getAttachment(INV_ATT_CALLBACK_KEY + paraIndex)), false);
                } catch (Exception e) {
                    throw new IOException(StringUtils.toString(e));
                }
            default:
                return inObject;
        }
    }
}