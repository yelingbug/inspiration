/*
 * Copyright 2018 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc;

import com.google.common.base.MoreObjects;
import javax.annotation.Nullable;

/**
 * A {@link ClientCall} which forwards all of its methods to another {@link ClientCall} which
 * may have a different sendMessage() message type.
 */
abstract class PartialForwardingClientCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {
  /**
   * 和{@link io.grpc.ForwardingClientCall.SimpleForwardingClientCall}不同的是{@link io.grpc.ForwardingClientCall.SimpleForwardingClientCall}中所有的方法
   * 全部委托给delegate(在其构造函数中传入的ClientCall实现)，而{@link PartialForwardingClientCall}可以对其消息的请求和相应有额外的定制策略，比如发送消息时
   * 可以有额外的编码(覆盖{@link PartialForwardingClientCall#sendMessage(Object)}方法)，收到消息时候可以有额外的解码(覆盖{@link PartialForwardingClientCall#start(Listener, Metadata)}
   * 中的{@link ClientCall.Listener#onMessage(Object)}方法)。
   *
   * {@link io.grpc.ForwardingClientCall.SimpleForwardingClientCall}是通过构造函数传入{@link ClientCall}，而{@link PartialForwardingClientCall}是没有构造函数的，如果要交给
   * 下一个{@link ClientCall}，那就只能channel.nextCall(...)。
   */

  /**
   * Returns the delegated {@code ClientCall}.
   */
  protected abstract ClientCall<?, ?> delegate();

  @Override
  public void request(int numMessages) {
    delegate().request(numMessages);
  }

  @Override
  public void cancel(@Nullable String message, @Nullable Throwable cause) {
    delegate().cancel(message, cause);
  }

  @Override
  public void halfClose() {
    delegate().halfClose();
  }

  @Override
  public void setMessageCompression(boolean enabled) {
    delegate().setMessageCompression(enabled);
  }

  @Override
  public boolean isReady() {
    return delegate().isReady();
  }

  @Override
  public Attributes getAttributes() {
    return delegate().getAttributes();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("delegate", delegate()).toString();
  }
}
