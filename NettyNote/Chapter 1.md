# C1 基础架构与入门

## 03|引导器作用：客户端和服务端启动都要做什么
### 引到器的使用
Netty服务端的启动过程主要有三步：
1. 配置线程池
2. Channel初始化
3. 端口绑定
#### 配置线程池
Netty的三种Reactor
1. 单线程
2. 多线程
3. 主从多线程
##### 单线程
所有任务均有一个线程完成
```Java
    EventLoopGroup group=new NioEventLoopGroup(1);
    ServerBootstrap b=new ServerBootstrap();
    b.group(group)
```
##### 多线程
与上面类似，变为多个线程，无参数时默认两倍CPU核数
```java
    EventLoopGroup group=new NioEventLoopGroup();
    ServerBootstrap b=new ServerBootstrap();
    b.group(group)
```
##### 主从多线程
主Reactor负责Accept然后注册到Channel上，从Reactor负责Channel生命周期内的所有IO事件
```java
    EventLoopGroup bossGroup=new NioEventLoopGroup();
    EventLoopGroup workerGroup=new NioEventLoopGroup();
    ServerBootstrap b=new ServerBootstrap();
    b.group(bossGroup,workerGroup)
```
#### Channel初始化
即设置Channel类型
```java
 b.channel(NioServerSocketChannel.class);
```
#### 注册ChannelHandler
```java
b.childHandler(new ChannelInitializer<SocketChannel>() {
 @Override
 public void initChannel(SocketChannel ch) {
    ch.pipeline()
        .addLast("codec", new HttpServerCodec())
        .addLast("compressor", new HttpContentCompressor())
        .addLast("aggregator", new HttpObjectAggregator(65536)) 
         .addLast("handler", new HttpServerHandler());
    }
})
```

## 04|事件调度层：为什么EventLoop是Netty的精髓

### Reactor 线程模型
Reactor作为事件分发器，将读写事件分发给对应的读写事件处理者
三种类型
1. 单线程模型
2. 多线程模型
3. 主从多线程模型

#### 单线程模型
一个线程干到死 

缺点：
- 能支持的并发不高
- IO操作时只能等待IO完成
- IO线程处于满负荷状态可能导致服务不可用

#### 多线程模型
将业务处理逻辑放在多线程里，其余的和单线程模型相同

#### 主从多线程模型
主Reactor主要负责接收连接，从Reactor主要负责将每个从主接收到的连接分配到子线程中，让该线程负责这个连接的生命周期


### EventLoop模型
EventLoop是一种事件等待和处理的程序模型，主要为解决多线程资源消耗高的问题。

每当事件发生时，将事件放入到事件队列，然后EventLoop从事件队列中取出事件，执行或分发到相关事件的监听者执行。
执行方式为立即执行，延后执行，定期执行

### 事件处理机制
NIOEvenLoop的事件处理机制采用的是无所串行化的设计思路



### EventLoop最佳实践

1. 网络连接建立过程中三次握手、安全认证的过程会消耗不少时间。这里建议采用 Boss 和 Worker 两个 EventLoopGroup，有助于分担 Reactor 线程的压力。
2. 由于 Reactor 线程模式适合处理耗时短的任务场景，对于耗时较长的 ChannelHandler 可以考虑维护一个业务线程池，将编解码后的数据封装成 Task 进行异步处理，避免 ChannelHandler 阻塞而造成 EventLoop 不可用。
3. 如果业务逻辑执行时间较短，建议直接在 ChannelHandler 中执行。例如编解码操作，这样可以避免过度设计而造成架构的复杂性。
4. 不宜设计过多的 ChannelHandler。对于系统性能和可维护性都会存在问题，在设计业务架构的时候，需要明确业务分层和 Netty 分层之间的界限。不要一味地将业务逻辑都添加到 ChannelHandler 中。


## 05|服务编排层：PipeLine如何协调各类Handler

### ChannelPipeline内部结构

[![WhZNWR.png](https://z3.ax1x.com/2021/07/26/WhZNWR.png)](https://imgtu.com/i/WhZNWR)

- ChannelPipeline由一组ChannelHandler组成
- 每个ChannelHandler由HeadContext包裹，保存ChannelHandler的生命周期的所有事件
- ChannelHandler分入站和出站

[![WhZtY9.png](https://z3.ax1x.com/2021/07/26/WhZtY9.png)](https://imgtu.com/i/WhZtY9)

ChannelPipeline 的双向链表分别维护了 HeadContext 和 TailContext 的头尾节点。我们自定义的 ChannelHandler 会插入到 Head 和 Tail 之间，这两个节点在 Netty 中已经默认实现了。

HeadContext 既实现了ChannelInBoundHandler也实现了ChannelOutBoundHandler

网络数据写入操作的入口就是由 HeadContext 节点完成的。HeadContext 作为 Pipeline 的头结点负责读取数据并开始传递 InBound 事件，当数据处理完成后，数据会反方向经过 Outbound 处理器，最终传递到 HeadContext，所以 HeadContext 又是处理 Outbound 事件的最后一站。此外 HeadContext 在传递事件之前，还会执行一些前置操作。

TailContext 只实现了 ChannelInboundHandler 接口。它会在 ChannelInboundHandler 调用链路的最后一步执行，主要用于终止 Inbound 事件传播，例如释放 Message 数据资源等。TailContext 节点作为 OutBound 事件传播的第一站，仅仅是将 OutBound 事件传递给上一个节点。

### ChannelHandler接口

#### ChannelInBoundHandler

| 事件回调方法              | 触发时机                                           |
| ------------------------- | -------------------------------------------------- |
| channelRegistered         | Channel 被注册到 EventLoop                         |
| channelUnregistered       | Channel 从 EventLoop 中取消注册                    |
| channelActive             | Channel 处于就绪状态，可以被读写                   |
| channelInactive           | Channel 处于非就绪状态Channel 可以从远端读取到数据 |
| channelRead               | Channel 可以从远端读取到数据                       |
| channelReadComplete       | Channel 读取数据完成                               |
| userEventTriggered        | 用户事件触发时                                     |
| channelWritabilityChanged | Channel 的写状态发生变化                           |



#### ChannelOutBoundHandler

[![WhZaS1.png](https://z3.ax1x.com/2021/07/26/WhZaS1.png)](https://imgtu.com/i/WhZaS1)

### 事件传播机制

Demo代码

```java
serverBootstrap.childHandler(new ChannelInitializer<SocketChannel>() {

    @Override

    public void initChannel(SocketChannel ch) {

        ch.pipeline()

                .addLast(new SampleInBoundHandler("SampleInBoundHandlerA", false))

                .addLast(new SampleInBoundHandler("SampleInBoundHandlerB", false))

                .addLast(new SampleInBoundHandler("SampleInBoundHandlerC", true));

        ch.pipeline()

                .addLast(new SampleOutBoundHandler("SampleOutBoundHandlerA"))

                .addLast(new SampleOutBoundHandler("SampleOutBoundHandlerB"))

                .addLast(new SampleOutBoundHandler("SampleOutBoundHandlerC"));

    }

}

public class SampleInBoundHandler extends ChannelInboundHandlerAdapter {

    private final String name;

    private final boolean flush;

    public SampleInBoundHandler(String name, boolean flush) {

        this.name = name;

        this.flush = flush;

    }

    @Override

    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        System.out.println("InBoundHandler: " + name);

        if (flush) {

            ctx.channel().writeAndFlush(msg);

        } else {

            super.channelRead(ctx, msg);

        }

    }

}



public class SampleOutBoundHandler extends ChannelOutboundHandlerAdapter {

    private final String name;

    public SampleOutBoundHandler(String name) {

        this.name = name;

    }

    @Override

    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

        System.out.println("OutBoundHandler: " + name);

        super.write(ctx, msg, promise);

    }

}

```

执行图示：

[![WhZYFJ.png](https://z3.ax1x.com/2021/07/26/WhZYFJ.png)](https://imgtu.com/i/WhZYFJ)

### 异常传播机制

ChannelPipeline 事件传播的实现采用了经典的责任链模式，调用链路环环相扣。ctx.fireExceptionCaugh 会将异常按顺序从 Head 节点传播到 Tail 节点。

如果用户没有对异常进行拦截处理，最后将由 Tail 节点统一处理。

异常最佳处理是在链路最后添加异常处理。
