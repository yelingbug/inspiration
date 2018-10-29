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
package com.alibaba.dubbo.common.bytecode;

import com.alibaba.dubbo.common.utils.ClassHelper;
import com.alibaba.dubbo.common.utils.ReflectUtils;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proxy.
 */

public abstract class Proxy {
    public static final InvocationHandler RETURN_NULL_INVOKER = new InvocationHandler() {
        public Object invoke(Object proxy, Method method, Object[] args) {
            return null;
        }
    };
    public static final InvocationHandler THROW_UNSUPPORTED_INVOKER = new InvocationHandler() {
        public Object invoke(Object proxy, Method method, Object[] args) {
            throw new UnsupportedOperationException("Method [" + ReflectUtils.getName(method) + "] unimplemented.");
        }
    };
    private static final AtomicLong PROXY_CLASS_COUNTER = new AtomicLong(0);
    private static final String PACKAGE_NAME = Proxy.class.getPackage().getName();
    private static final Map<ClassLoader, Map<String, Object>> ProxyCacheMap = new WeakHashMap<ClassLoader, Map<String, Object>>();

    private static final Object PendingGenerationMarker = new Object();

    protected Proxy() {
    }

    /**
     * Get proxy.
     *
     * @param ics interface class array.
     * @return Proxy instance.
     */
    public static Proxy getProxy(Class<?>... ics) {
        return getProxy(ClassHelper.getClassLoader(Proxy.class), ics);
    }

    /**
     * Get proxy.
     *
     * @param cl  class loader.
     * @param ics interface class array.
     * @return Proxy instance.
     */
    public static Proxy getProxy(ClassLoader cl, Class<?>... ics) {
        if (ics.length > 65535)
            throw new IllegalArgumentException("interface limit exceeded");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ics.length; i++) {
            String itf = ics[i].getName();
            if (!ics[i].isInterface())
                throw new RuntimeException(itf + " is not a interface.");

            Class<?> tmp = null;
            try {
                tmp = Class.forName(itf, false, cl);
            } catch (ClassNotFoundException e) {
            }

            if (tmp != ics[i])
                throw new IllegalArgumentException(ics[i] + " is not visible from class loader");

            sb.append(itf).append(';');
        }

        // use interface class name list as key.
        String key = sb.toString();

        // get cache by class loader.
        Map<String, Object> cache;
        synchronized (ProxyCacheMap) {
            cache = ProxyCacheMap.get(cl);
            if (cache == null) {
                cache = new HashMap<String, Object>();
                ProxyCacheMap.put(cl, cache);
            }
        }

        Proxy proxy = null;
        synchronized (cache) {
            do {
                Object value = cache.get(key);
                if (value instanceof Reference<?>) {
                    proxy = (Proxy) ((Reference<?>) value).get();
                    if (proxy != null)
                        return proxy;
                }

                if (value == PendingGenerationMarker) {//保证构建Proxy是串行的，在finally块中会notify
                    try {
                        cache.wait();
                    } catch (InterruptedException e) {
                    }
                } else {
                    cache.put(key, PendingGenerationMarker);
                    break;
                }
            }
            while (true);
        }

        long id = PROXY_CLASS_COUNTER.getAndIncrement();
        String pkg = null;
        ClassGenerator ccp = null, ccm = null;
        try {
            ccp = ClassGenerator.newInstance(cl);

            Set<String> worked = new HashSet<String>();
            List<Method> methods = new ArrayList<Method>();

            for (int i = 0; i < ics.length; i++) {
                if (!Modifier.isPublic(ics[i].getModifiers())) {
                    String npkg = ics[i].getPackage().getName();
                    if (pkg == null) {
                        pkg = npkg;
                    } else {
                        if (!pkg.equals(npkg))
                            throw new IllegalArgumentException("non-public interfaces from different packages");
                    }
                }
                ccp.addInterface(ics[i]);//准备生成的这个类继承多个接口Class<?>...ics

                for (Method method : ics[i].getMethods()) {//对每个接口的方法生成代理方法实现
                    String desc = ReflectUtils.getDesc(method);
                    if (worked.contains(desc))
                        continue;
                    worked.add(desc);

                    int ix = methods.size();
                    Class<?> rt = method.getReturnType();
                    Class<?>[] pts = method.getParameterTypes();

                    StringBuilder code = new StringBuilder("Object[] args = new Object[").append(pts.length).append("];");
                    for (int j = 0; j < pts.length; j++)
                        code.append(" args[").append(j).append("] = ($w)$").append(j + 1).append(";");
                    code.append(" Object ret = handler.invoke(this, methods[" + ix + "], args);");
                    if (!Void.TYPE.equals(rt))
                        code.append(" return ").append(asArgument(rt, "ret")).append(";");

                    methods.add(method);
                    ccp.addMethod(method.getName(), method.getModifiers(), rt, pts, method.getExceptionTypes(), code.toString());
                }
            }

            if (pkg == null)
                pkg = PACKAGE_NAME;

            // create ProxyInstance class.
            String pcn = pkg + ".proxy" + id;//待生成的类全路径为<pkg>.proxy<id>，这里的id原子自增
            ccp.setClassName(pcn);
            ccp.addField("public static java.lang.reflect.Method[] methods;");
            ccp.addField("private " + InvocationHandler.class.getName() + " handler;");
            ccp.addConstructor(Modifier.PUBLIC, new Class<?>[]{InvocationHandler.class}, new Class<?>[0], "handler=$1;");
            ccp.addDefaultConstructor();
            Class<?> clazz = ccp.toClass();//待生成的类构建Class实例，位于ccp创建时的ClassLoader中，这很重要。因为下面动态生成的类继承Proxy类，并实现其抽象方法
            //newInstance()时，返回的就是这里生成的类。
            clazz.getField("methods").set(null, methods.toArray(new Method[0]));

            /**
             * 我们以一个类为例，假设调用代码：
             * <pre>
             *     new JavassistProxyFactory().getProxy(new MyInvoker<DemoService>(URL.valueOf("injvm://127.0.0.1/TestService")));
             * </pre>
             *
             * MyInvoker的代码为：
             *
             * <pre>
             * public class MyInvoker<T> implements Invoker<T> {
             *      URL url;
             *      Class<T> type;
             *      boolean hasException = false;
             *
             *      public MyInvoker(URL url) {
             *          this.url = url;
             *          type = (Class<T>) DemoService.class;
             *      }
             *
             *      public MyInvoker(URL url, boolean hasException) {
             *          this.url = url;
             *          type = (Class<T>) DemoService.class;
             *          this.hasException = hasException;
             *      }
             *
             *      public Class<T> getInterface() { return type; }
             *
             *      public URL getUrl() { return url; }
             *
             *      public boolean isAvailable() { return false; }
             *
             *      public Result invoke(Invocation invocation) throws RpcException {
             *          RpcResult result = new RpcResult();
             *          if (hasException == false) {
             *              result.setValue("alibaba");
             *              return result;
             *          } else {
             *              result.setException(new RuntimeException("mocked exception"));
             *              return result;
             *          }
             *      }
             *
             *      public void destroy() { }
             *  }
             *
             *  </pre>
             *
             *  这里我们看到MyInvoker中其实拥有type属性，为DemoService这个接口，看看DemoService接口的代码：
             *
             *  <pre>
             *      public interface DemoService {
             *          void sayHello(String name);
             *          String echo(String text);
             *          long timestamp();
             *          String getThreadName();
             *          int getSize(String[] strs);
             *          int getSize(Object[] os);
             *          Object invoke(String service, String method) throws Exception;
             *          int stringLength(String str);
             *          Type enumlength(Type... types);
             *          Type enumlength(Type type);
             *          String get(CustomArgument arg1);
             *          byte getbyte(byte arg);
             *      }
             *  </pre>
             *
             *  另外在AbstractProxyFactory#getProxy(Invoker)中还主动添加了一个EchoService接口，应该是为了测试用途，这个接口的代码很简单：
             *  <pre>
             *      public interface EchoService {
             *          Object $echo(Object message);
             *      }
             *  </pre>
             *
             *  OK，看到上面两个接口，通过这里的逻辑生成的ccp的代码为：
             *
             *  public class <pkg>.proxy0 implements DemoService, EchoService {//假设这里的id为０
             *      public static java.lang.reflect.Method[] methods = xxx;//注意，这里的methods在ccp.toClass()之后通过clazz.getField("methods").set(null, methods.toArray(new Method[0]));已经赋值了，
             *      　　　　　　　　　　　　　　　　　　　　　　　//也就是说这里的methods是两个接口所有的java.lang.Method集合
             *      private InvocationHandler handler;
             *
             *      public <pkg>.proxy0() {}//ccp.addDefaultConstructor()生成的
             *
             *      public <pkg>.proxy0(InvocationHander $1) {//addConstructor(Modifier.PUBLIC, new Class<?>[]{InvocationHandler.class}, new Class<?>[0], "handler=$1;");生成的
             *          handler=$1;
             *      }
             *
             *     //这一段是DemoService接口的动态生成
             *      public void sayHello(java.lang.String arg0){Object[] args = new Object[1]; args[0] = ($w)$1; Object ret = handler.invoke(this, methods[0], args);}
             *
             *      public java.lang.String echo(java.lang.String arg0){Object[] args = new Object[1]; args[0] = ($w)$1; Object ret = handler.invoke(this, methods[1], args); return (java.lang.String)ret;}
             *
             *      public java.lang.String getThreadName(){Object[] args = new Object[0]; Object ret = handler.invoke(this, methods[2], args); return (java.lang.String)ret;}
             *
             *      public int stringLength(java.lang.String arg0){Object[] args = new Object[1]; args[0] = ($w)$1; Object ret = handler.invoke(this, methods[3], args); return ret==null?(int)0:((Integer)ret).intValue();}
             *
             *      public com.y2.duboo.Type enumlength(com.y2.duboo.Type[] arg0){Object[] args = new Object[1]; args[0] = ($w)$1; Object ret = handler.invoke(this, methods[4], args); return (com.y2.duboo.Type)ret;}
             *
             *      public byte getbyte(byte arg0){Object[] args = new Object[1]; args[0] = ($w)$1; Object ret = handler.invoke(this, methods[5], args); return ret==null?(byte)0:((Byte)ret).byteValue();}
             *
             *      public java.lang.Object invoke(java.lang.String arg0,java.lang.String arg1) throws java.lang.Exception{Object[] args = new Object[2]; args[0] = ($w)$1; args[1] = ($w)$2; Object ret = handler.invoke(this, methods[6], args); return (java.lang.Object)ret;}
             *
             *      public java.lang.String get(com.y2.duboo.CustomArgument arg0){Object[] args = new Object[1]; args[0] = ($w)$1; Object ret = handler.invoke(this, methods[7], args); return (java.lang.String)ret;}
             *
             *      public long timestamp(){Object[] args = new Object[0]; Object ret = handler.invoke(this, methods[8], args); return ret==null?(long)0:((Long)ret).longValue();}
             *
             *      public int getSize(java.lang.String[] arg0){Object[] args = new Object[1]; args[0] = ($w)$1; Object ret = handler.invoke(this, methods[9], args); return ret==null?(int)0:((Integer)ret).intValue();}
             *
             *      public int getSize(java.lang.Object[] arg0){Object[] args = new Object[1]; args[0] = ($w)$1; Object ret = handler.invoke(this, methods[10], args); return ret==null?(int)0:((Integer)ret).intValue();}
             *
             *      //这一段是EchoService的动态生成
             *      public java.lang.Object $echo(java.lang.Object arg0){Object[] args = new Object[1]; args[0] = ($w)$1; Object ret = handler.invoke(this, methods[11], args); return (java.lang.Object)ret;}
             *  }
             *
             *  上面生成的代码中有一些特殊的变量是javassist中独有的，比如$w，$0，$1，$2...等，详细情况参照官方文档说明，这里着重指出的是$1代表方法的第一个参数，$w用于包装
             *  类型的转型，当参数类型为原始数据类型时，需要使用该变量将原始类型转换成为包装类型，比如(Integer)5，从上面的生成代码中可以看出args是一个Object数组，所以一定
             *  存放是对象类型。
             *
             *  生成的类中有个构造函数传入{@link java.lang.reflect.InvocationHandler InvocationHandler}，其中的所有方法调用都通过这个句柄来进行，实际上这个就是ＪＤＫ的动态
             *  代理的实现方式。
             *
             *  生成的这个类主要用于下面的代码，下面的ccm生成类继承{@link Proxy}类，实现其抽象方法newInstance()，而newInstance()的方法体就是返回上面创建的这个类实例。
             *
             *  public class Proxy1 extends Proxy {
             *    public Proxy1() {}
             *
             *    public Object newInstance(InvocationHandler h) {
             *      return new <pkg>.proxy0(h);
             *    }
             *  }
             *
             *  THAT IS IT!
             *
             *  对接口来说，要代理
             *
             *
             *
             */

            // create Proxy class.
            String fcn = Proxy.class.getName() + id;
            ccm = ClassGenerator.newInstance(cl);
            ccm.setClassName(fcn);
            ccm.addDefaultConstructor();
            ccm.setSuperClass(Proxy.class);
            ccm.addMethod("public Object newInstance(" + InvocationHandler.class.getName() + " h){ return new " + pcn + "($1); }");
            Class<?> pc = ccm.toClass();
            proxy = (Proxy) pc.newInstance();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            // release ClassGenerator
            if (ccp != null)
                ccp.release();
            if (ccm != null)
                ccm.release();
            synchronized (cache) {
                if (proxy == null)
                    cache.remove(key);
                else
                    cache.put(key, new WeakReference<Proxy>(proxy));
                cache.notifyAll();
            }
        }
        return proxy;
    }

    private static String asArgument(Class<?> cl, String name) {
        if (cl.isPrimitive()) {
            if (Boolean.TYPE == cl)
                return name + "==null?false:((Boolean)" + name + ").booleanValue()";
            if (Byte.TYPE == cl)
                return name + "==null?(byte)0:((Byte)" + name + ").byteValue()";
            if (Character.TYPE == cl)
                return name + "==null?(char)0:((Character)" + name + ").charValue()";
            if (Double.TYPE == cl)
                return name + "==null?(double)0:((Double)" + name + ").doubleValue()";
            if (Float.TYPE == cl)
                return name + "==null?(float)0:((Float)" + name + ").floatValue()";
            if (Integer.TYPE == cl)
                return name + "==null?(int)0:((Integer)" + name + ").intValue()";
            if (Long.TYPE == cl)
                return name + "==null?(long)0:((Long)" + name + ").longValue()";
            if (Short.TYPE == cl)
                return name + "==null?(short)0:((Short)" + name + ").shortValue()";
            throw new RuntimeException(name + " is unknown primitive type.");
        }
        return "(" + ReflectUtils.getName(cl) + ")" + name;
    }

    /**
     * get instance with default handler.
     *
     * @return instance.
     */
    public Object newInstance() {
        return newInstance(THROW_UNSUPPORTED_INVOKER);
    }

    /**
     * get instance with special handler.
     *
     * @return instance.
     */
    abstract public Object newInstance(InvocationHandler handler);
}