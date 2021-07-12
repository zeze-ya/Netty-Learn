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