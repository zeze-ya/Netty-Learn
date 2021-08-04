# Netty编解码相关



## 06|粘包/拆包问题：如何获取一个完整的网络包？

### 拆包/粘包

TCP传输协议是面向流的，没有数据包的界限。所以每次传输的网络包大小不同，也就导致了发送出去的数据可能不是一个完整的数据包，也就是拆包，或者将多个数据包合成一个网络包然后发送出去，也就是所谓的粘包。

### 拆包/粘包的基本情况

[![Whm154.png](https://z3.ax1x.com/2021/07/26/Whm154.png)](https://imgtu.com/i/Whm154)

1. A和B两个数据包都通过单独的网络包发送出去，服务端分别单独解析即可
2. A和B通过一个网络包发送出去，服务端需要对其进行拆分
3. A和B的一部分是一个网络包，B的另一部分通过一个网络包发送出去，服务端要拆分出B的前半部分缓存起来，等待
   B的后一部分
4. A的一部分通过网络包发送出去，A的另一部分和B通过一个网络包发送出去，要先缓存A的一部分，等到第二个网络包到解析出来组成完整的A
5. A分成了多个网络包发送出去，缓存A的每部分并拼接

### 解决办法：自定义应用层通信协议

常见的几种协议方式为

#### 定长消息

所有消息长度都一定，如果不足用空位补齐。

- 缺点
  1. 当消息长度不确定时会浪费网络资源，必须保证消息长度满足你所发消息的最大值

#### 特定分隔符

使用特殊的字符对消息进行拆分，在发送消息的尾部添加特殊字符串。

- 缺点
  1. 如果消息中会出现特殊字符，需要考虑冲突处理

比较推荐的做法是将消息进行编码，例如 base64 编码，然后可以选择 64 个编码字符之外的字符作为特定分隔符。



#### 消息长度+消息内容

```
消息头     消息体

+--------+----------+

| Length |  Content |

+--------+----------+

```

最常见的协议，消息头描述消息的总长度，接收方先解析消息头获取到总长度，然后读取总长度的字符。

消息长度 + 消息内容的使用方式非常灵活，且不会存在消息定长法和特定分隔符法的明显缺陷。当然在消息头中不仅只限于存放消息的长度，而且可以自定义其他必要的扩展字段，例如消息版本、算法类型等。



## 07|接头暗语：如何利用Netty实现自定义协议通信

### 通信协议设计

通信协议要素

1. 魔数：固定几个字节表示，通信双方商定的暗号，防止任何人随便向服务器的端口上发送数据
2. 协议版本号：对不同版本的协议解析是不同的
3. 序列化算法：何种方法转换为二进制
4. 报文类型
5. 长度域字段
6. 请求数据
7. 状态
8. 保留字段

### Netty如何实现自定义通信协议

- 常用编码器
  1. MessageToByteEncoder 对象编码成字节流
  2. MessageToMessageEncoder 一种消息类型编码成另一种消息类型
- 常用解码器
  1. ByteToMessageDecoder/ReplayingDecoder 将字节流解码为消息对象
  2. MessageToMessageDecoder 将一种消息类型解码为另一种消息类型



#### 编码器UML类图

[![WxdhCj.png](https://z3.ax1x.com/2021/08/01/WxdhCj.png)](https://imgtu.com/i/WxdhCj)

两种编码器都是OutBoundHandler的实现，即操作出栈数据



#### MessageToByteEncoder

```java
@Override

public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

    ByteBuf buf = null;

    try {

        if (acceptOutboundMessage(msg)) { // 1. 消息类型是否匹配

            @SuppressWarnings("unchecked")

            I cast = (I) msg;

            buf = allocateBuffer(ctx, cast, preferDirect); // 2. 分配 ByteBuf 资源

            try {

                encode(ctx, cast, buf); // 3. 执行 encode 方法完成数据编码

            } finally {

                ReferenceCountUtil.release(cast);

            }

            if (buf.isReadable()) {

                ctx.write(buf, promise); // 4. 向后传递写事件

            } else {

                buf.release();

                ctx.write(Unpooled.EMPTY_BUFFER, promise);

            }

            buf = null;

        } else {

            ctx.write(msg, promise);

        }

    } catch (EncoderException e) {

        throw e;

    } catch (Throwable e) {

        throw new EncoderException(e);

    } finally {

        if (buf != null) {

            buf.release();

        }

    }

}

```

MessageToByteEncoder 重写了 ChanneOutboundHandler 的 write() 方法，其主要逻辑分为以下几个步骤：

1. acceptOutboundMessage 判断是否有匹配的消息类型，如果匹配需要执行编码流程，如果不匹配直接继续传递给下一个 ChannelOutboundHandler；
2. 分配 ByteBuf 资源，默认使用**堆外内存；**
3. 调用子类实现的 encode 方法完成数据编码，一旦消息被成功编码，会通过调用 ReferenceCountUtil.release(cast) 自动释放；
4. 如果 ByteBuf 可读，说明已经成功编码得到数据，然后写入 ChannelHandlerContext 交到下一个节点；如果 ByteBuf 不可读，则释放 ByteBuf 资源，向下传递空的 ByteBuf 对象。

使用时需要做的就是去实现encode方法

```java
public class StringToByteEncoder extends MessageToByteEncoder<String> {

        @Override

        protected void encode(ChannelHandlerContext channelHandlerContext, String data, ByteBuf byteBuf) throws Exception {

            byteBuf.writeBytes(data.getBytes());

        }

}
```

#### MessageToMessageEncoder

使用方法与MessageToByte类似。

与 MessageToByteEncoder 不同的是，MessageToMessageEncoder 是将一种格式的消息转换为另外一种格式的消息。其中第二个 Message 所指的可以是任意一个对象，如果该对象是 ByteBuf 类型，那么基本上和 MessageToByteEncoder 的实现原理是一致的

源码示例如下：将 CharSequence 类型（String、StringBuilder、StringBuffer 等）转换成 ByteBuf 类型，结合 StringDecoder 可以直接实现 String 类型数据的编解码。



```java
@Override

protected void encode(ChannelHandlerContext ctx, CharSequence msg, List<Object> out) throws Exception {

    if (msg.length() == 0) {

        return;

    }

    out.add(ByteBufUtil.encodeString(ctx.alloc(), CharBuffer.wrap(msg), charset));

}

```



#### 解码器UML类图

[![Wx4FdH.png](https://z3.ax1x.com/2021/08/01/Wx4FdH.png)](https://imgtu.com/i/Wx4FdH)

两种编码器都是InBoundHandler的实现，即入栈数据的操作但解码器会比编码器更难一点，应为他包含了拆包/粘包的情况。

#### ByteToMessageDecoder

```java
public abstract class ByteToMessageDecoder extends ChannelInboundHandlerAdapter {

    protected abstract void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception;

    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {

        if (in.isReadable()) {

            decodeRemovalReentryProtection(ctx, in, out);

        }

    }

}
```

decode() 是用户必须实现的抽象方法，在该方法在调用时需要传入接收的数据 ByteBuf，及用来添加编码后消息的 List。由于 TCP 粘包问题，ByteBuf 中可能包含多个有效的报文，或者不够一个完整的报文。Netty 会重复回调 decode() 方法，直到没有解码出新的完整报文可以添加到 List 当中，或者 ByteBuf 没有更多可读取的数据为止。如果此时 List 的内容不为空，那么会传递给 ChannelPipeline 中的下一个ChannelInboundHandler。

decodeLast()在Channel关闭后会被调用一次，主要用于处理ByteBuf最后剩余的字节数据。默认实现是调用decode方法，有特殊需求可以重写。

ByteToMessageDecoder 还有一个抽象子类是 ReplayingDecoder。它封装了缓冲区的管理，在读取缓冲区数据时，你无须再对字节长度进行检查。因为如果没有足够长度的字节数据，ReplayingDecoder 将终止解码操作。ReplayingDecoder 的性能相比直接使用 ByteToMessageDecoder 要慢，大部分情况下并不推荐使用 ReplayingDecoder。



#### MessageToMessageDecoder

MessageToMessageDecoder 与 ByteToMessageDecoder 作用类似。与 ByteToMessageDecoder 不同的是 MessageToMessageDecoder 并不会对数据报文进行缓存，它主要用作转换消息模型。



[![WxHpQI.png](https://z3.ax1x.com/2021/08/01/WxHpQI.png)](https://imgtu.com/i/WxHpQI)



### 示例

```java
/*

+---------------------------------------------------------------+

| 魔数 2byte | 协议版本号 1byte | 序列化算法 1byte | 报文类型 1byte  |

+---------------------------------------------------------------+

| 状态 1byte |        保留字段 4byte     |      数据长度 4byte     | 

+---------------------------------------------------------------+

|                   数据内容 （长度不定）                          |

+---------------------------------------------------------------+

 */

@Override

public final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {

    // 判断 ByteBuf 可读取字节

    if (in.readableBytes() < 14) { 

        return;

    }

    in.markReaderIndex(); // 标记 ByteBuf 读指针位置

    in.skipBytes(2); // 跳过魔数

    in.skipBytes(1); // 跳过协议版本号

    byte serializeType = in.readByte();

    in.skipBytes(1); // 跳过报文类型

    in.skipBytes(1); // 跳过状态字段

    in.skipBytes(4); // 跳过保留字段

    int dataLength = in.readInt();

    if (in.readableBytes() < dataLength) {

        in.resetReaderIndex(); // 重置 ByteBuf 读指针位置

        return;

    }

    byte[] data = new byte[dataLength];

    in.readBytes(data);

    SerializeService serializeService = getSerializeServiceByType(serializeType);

    Object obj = serializeService.deserialize(data);

    if (obj != null) {

        out.add(obj);

    }

}

```

## 08|开箱即用：Netty支持哪些常用的解码器？

### 固定长度解码器 FixedLengthFrameDecoder

直接通过构造函数设置固定长度的大小 frameLength，无论接收方一次获取多大的数据，都会严格按照 frameLength 进行解码。

### 特殊分隔符解码器 DelimiterBasedFrameDecoder

属性：

- delimiters

delimiters 指定特殊分隔符，通过写入 ByteBuf 作为参数传入。delimiters 的类型是 ByteBuf 数组，所以我们可以同时指定多个分隔符，但是最终会选择长度最短的分隔符进行消息拆分。

- maxLength

maxLength 是报文最大长度的限制。如果超过 maxLength 还没有检测到指定分隔符，将会抛出 TooLongFrameException。可以说 maxLength 是对程序在极端情况下的一种保护措施。

- failFast

failFast 与 maxLength 需要搭配使用，通过设置 failFast 可以控制抛出 TooLongFrameException 的时机，可以说 Netty 在细节上考虑得面面俱到。如果 failFast=true，那么在超出 maxLength 会立即抛出 TooLongFrameException，不再继续进行解码。如果 failFast=false，那么会等到解码出一个完整的消息后才会抛出 TooLongFrameException。

- stripDelimiter

stripDelimiter 的作用是判断解码后得到的消息是否去除分隔符。如果 stripDelimiter=false，特定分隔符为 \n



### 长度域解码器 LengthFieldBasedFrameDecoder

长度域解码器 LengthFieldBasedFrameDecoder 是解决 TCP 拆包/粘包问题最常用的解码器。

#### 长度域解码器特有属性

```java
// 长度字段的偏移量，也就是存放长度数据的起始位置
private final int lengthFieldOffset; 
// 长度字段所占用的字节数
private final int lengthFieldLength; 
/*
 * 消息长度的修正值
 *
 * 在很多较为复杂一些的协议设计中，长度域不仅仅包含消息的长度，而且包含其他的数据，如版本号、数据类型、数据状态等，那么这时候我们需要使用 lengthAdjustment 进行修正
 * 
 * lengthAdjustment = 包体的长度值 - 长度域的值
 *
 */
private final int lengthAdjustment; 
// 解码后需要跳过的初始字节数，也就是消息内容字段的起始位置
private final int initialBytesToStrip;
// 长度字段结束的偏移量，lengthFieldEndOffset = lengthFieldOffset + lengthFieldLength
private final int lengthFieldEndOffset;
```

#### 与固定长度解码器和特定分隔符解码器相似的属性

```java
private final int maxFrameLength; // 报文最大限制长度

private final boolean failFast; // 是否立即抛出 TooLongFrameException，与 maxFrameLength 搭配使用

private boolean discardingTooLongFrame; // 是否处于丢弃模式

private long tooLongFrameLength; // 需要丢弃的字节数

private long bytesToDiscard; // 累计丢弃的字节数

```

#### 示例一 消息长度+消息内容

消息结构

```jaav
BEFORE DECODE (14 bytes)         AFTER DECODE (14 bytes)

+--------+----------------+      +--------+----------------+

| Length | Actual Content |----->| Length | Actual Content |

| 0x000C | "HELLO, WORLD" |      | 0x000C | "HELLO, WORLD" |

+--------+----------------+      +--------+----------------+

```

上述协议是最基本的格式，报文只包含消息长度 Length 和消息内容 Content 字段，其中 Length 为 16 进制表示，共占用 2 字节，Length 的值 0x000C 代表 Content 占用 12 字节。该协议对应的解码器参数组合如下：

- lengthFieldOffset = 0，因为 Length 字段就在报文的开始位置。
- lengthFieldLength = 2，协议设计的固定长度。
- lengthAdjustment = 0，Length 字段只包含消息长度，不需要做任何修正。
- initialBytesToStrip = 0，解码后内容依然是 Length + Content，不需要跳过任何初始字节。

####  示例二 解码结果需要截断

```java
BEFORE DECODE (14 bytes)         AFTER DECODE (12 bytes)

+--------+----------------+      +----------------+

| Length | Actual Content |----->| Actual Content |

| 0x000C | "HELLO, WORLD" |      | "HELLO, WORLD" |

+--------+----------------+      +----------------+

```

示例 2 和示例 1 的区别在于解码后的结果只包含消息内容，其他的部分是不变的。该协议对应的解码器参数组合如下：

- lengthFieldOffset = 0，因为 Length 字段就在报文的开始位置。
- lengthFieldLength = 2，协议设计的固定长度。
- lengthAdjustment = 0，Length 字段只包含消息长度，不需要做任何修正。
- initialBytesToStrip = 2，跳过 Length 字段的字节长度，解码后 ByteBuf 中只包含 Content字段。

#### 示例 三：长度字段包含消息长度和消息内容所占的字节。

```java
BEFORE DECODE (14 bytes)         AFTER DECODE (14 bytes)

+--------+----------------+      +--------+----------------+

| Length | Actual Content |----->| Length | Actual Content |

| 0x000E | "HELLO, WORLD" |      | 0x000E | "HELLO, WORLD" |

+--------+----------------+      +--------+----------------+

```

与前两个示例不同的是，示例 3 的 Length 字段包含 Length 字段自身的固定长度以及 Content 字段所占用的字节数，Length 的值为 0x000E（2 + 12 = 14 字节），在 Length 字段值（14 字节）的基础上做 lengthAdjustment（-2）的修正，才能得到真实的 Content 字段长度，所以对应的解码器参数组合如下：

- lengthFieldOffset = 0，因为 Length 字段就在报文的开始位置。
- lengthFieldLength = 2，协议设计的固定长度。
- **lengthAdjustment = -2，长度字段为 14 字节，Content是12字节，所以需要减 2 才是拆包所需要的长度。**
- initialBytesToStrip = 0，解码后内容依然是 Length + Content，不需要跳过任何初始字节。

#### 示例 四：基于长度字段偏移的解码

```java
BEFORE DECODE (17 bytes)                      AFTER DECODE (17 bytes)

+----------+----------+----------------+      +----------+----------+----------------+

| Header 1 |  Length  | Actual Content |----->| Header 1 |  Length  | Actual Content |

|  0xCAFE  | 0x00000C | "HELLO, WORLD" |      |  0xCAFE  | 0x00000C | "HELLO, WORLD" |

+----------+----------+----------------+      +----------+----------+----------------+

```

示例 4 中 Length 字段不再是报文的起始位置，Length 字段的值为 0x00000C，表示 Content 字段占用 12 字节，该协议对应的解码器参数组合如下：

- lengthFieldOffset = 2，需要跳过 Header 1 所占用的 2 字节，才是 Length 的起始位置。
- lengthFieldLength = 3，协议设计的固定长度。
- lengthAdjustment = 0，Length 字段只包含消息长度，不需要做任何修正。
- initialBytesToStrip = 0，解码后内容依然是完整的报文，不需要跳过任何初始字节。

#### 示例 五：长度字段与内容字段不再相邻。

```java
BEFORE DECODE (17 bytes)                      AFTER DECODE (17 bytes)

+----------+----------+----------------+      +----------+----------+----------------+

|  Length  | Header 1 | Actual Content |----->|  Length  | Header 1 | Actual Content |

| 0x00000C |  0xCAFE  | "HELLO, WORLD" |      | 0x00000C |  0xCAFE  | "HELLO, WORLD" |

+----------+----------+----------------+      +----------+----------+----------------+

```

示例 5 中的 Length 字段之后是 Header 1，Length 与 Content 字段不再相邻。Length 字段所表示的内容略过了 Header 1 字段，所以也需要通过 lengthAdjustment 修正才能得到 Header + Content 的内容。示例 5 所对应的解码器参数组合如下：

- lengthFieldOffset = 0，因为 Length 字段就在报文的开始位置。
- lengthFieldLength = 3，协议设计的固定长度。
- **lengthAdjustment = 2，由于 Header + Content 一共占用 2 + 12 = 14 字节，所以 Content（12 字节）加上 lengthAdjustment（2 字节）才能得到 Header + Content 的内容（14 字节）。**
- initialBytesToStrip = 0，解码后内容依然是完整的报文，不需要跳过任何初始字节。

#### 示例 六：基于长度偏移和长度修正的解码

```java
BEFORE DECODE (16 bytes)                       AFTER DECODE (13 bytes)

+------+--------+------+----------------+      +------+----------------+

| HDR1 | Length | HDR2 | Actual Content |----->| HDR2 | Actual Content |

| 0xCA | 0x000C | 0xFE | "HELLO, WORLD" |      | 0xFE | "HELLO, WORLD" |

+------+--------+------+----------------+      +------+----------------+

```

示例 6 中 Length 字段前后分为别 HDR1 和 HDR2 字段，各占用 1 字节，所以既需要做长度字段的偏移，也需要做 lengthAdjustment 修正，具体修正的过程与 示例 5 类似。对应的解码器参数组合如下：

- lengthFieldOffset = 1，需要跳过 HDR1 所占用的 1 字节，才是 Length 的起始位置。
- lengthFieldLength = 2，协议设计的固定长度。
- lengthAdjustment = 1，由于 HDR2 + Content 一共占用 1 + 12 = 13 字节，所以 Content（12 字节）加上 lengthAdjustment（1）才能得到 HDR2 + Content 的内容（13 字节）。
- initialBytesToStrip = 3，解码后跳过 HDR1 和 Length 字段，共占用 3 字节。

#### 示例 七 长度字段包含除 Content 外的多个其他字段

```java
BEFORE DECODE (16 bytes)                       AFTER DECODE (13 bytes)

+------+--------+------+----------------+      +------+----------------+

| HDR1 | Length | HDR2 | Actual Content |----->| HDR2 | Actual Content |

| 0xCA | 0x0010 | 0xFE | "HELLO, WORLD" |      | 0xFE | "HELLO, WORLD" |

+------+--------+------+----------------+      +------+----------------+

```

示例 7 与 示例 6 的区别在于 Length 字段记录了整个报文的长度，包含 Length 自身所占字节、HDR1 、HDR2 以及 Content 字段的长度，解码器需要知道如何进行 lengthAdjustment 调整，才能得到 HDR2 和 Content 的内容。所以我们可以采用如下的解码器参数组合：

- lengthFieldOffset = 1，需要跳过 HDR1 所占用的 1 字节，才是 Length 的起始位置。
- lengthFieldLength = 2，协议设计的固定长度。
- lengthAdjustment = -3，整个包（16 字节）需要减去 HDR1（1 字节） 和 Length 自身所占字节长度（2 字节）才能得到 HDR2 和 Content 的内容（1 + 12 = 13 字节）。
- initialBytesToStrip = 3，解码后跳过 HDR1 和 Length 字段，共占用 3 字节。

#### 习题： 设计自定义协议参数

```java
+---------------------------------------------------------------+

| 魔数 2byte | 协议版本号 1byte | 序列化算法 1byte | 报文类型 1byte  |

+---------------------------------------------------------------+

| 状态 1byte |        保留字段 4byte     |      数据长度 4byte     | 

+---------------------------------------------------------------+

|                   数据内容 （长度不定）                          |

+---------------------------------------------------------------+

```



## 09| 数据传输:writeAndFlush 处理流程剖析

### writeAndFlush 事件传播分析

有如下代码：

```java
public class EchoServer {

    public void startEchoServer(int port) throws Exception {

        EventLoopGroup bossGroup = new NioEventLoopGroup();

        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {

            ServerBootstrap b = new ServerBootstrap();

            b.group(bossGroup, workerGroup)

                    .channel(NioServerSocketChannel.class)

                    .childHandler(new ChannelInitializer<SocketChannel>() {

                        @Override

                        public void initChannel(SocketChannel ch) {

                            ch.pipeline().addLast(new FixedLengthFrameDecoder(10));

                            ch.pipeline().addLast(new ResponseSampleEncoder());

                            ch.pipeline().addLast(new RequestSampleHandler());

                        }

                    });

            ChannelFuture f = b.bind(port).sync();

            f.channel().closeFuture().sync();

        } finally {

            bossGroup.shutdownGracefully();

            workerGroup.shutdownGracefully();

        }

    }

    public static void main(String[] args) throws Exception {

        new EchoServer().startEchoServer(8088);

    }

}
```

```java
public class ResponseSampleEncoder extends MessageToByteEncoder<ResponseSample> {

    @Override

    protected void encode(ChannelHandlerContext ctx, ResponseSample msg, ByteBuf out) {

        if (msg != null) {

            out.writeBytes(msg.getCode().getBytes());

            out.writeBytes(msg.getData().getBytes());

            out.writeLong(msg.getTimestamp());

        }

    }

}

```



```java
public class RequestSampleHandler extends ChannelInboundHandlerAdapter {

    @Override

    public void channelRead(ChannelHandlerContext ctx, Object msg) {

        String data = ((ByteBuf) msg).toString(CharsetUtil.UTF_8);

        ResponseSample response = new ResponseSample("OK", data, System.currentTimeMillis());

        ctx.channel().writeAndFlush(response);

    }

}

```



我们可以得到一个如下的流程图：

![](https://www.hualigs.cn/image/610aa2662a957.jpg)



 writeAndFlush 是特有的出站操作

DefaultChannelPipeline 类中调用的 Tail 节点 writeAndFlush 方法。

```java
@Override

public final ChannelFuture writeAndFlush(Object msg) {

    return tail.writeAndFlush(msg);

}

```

继续跟进 tail.writeAndFlush 的源码，最终会定位到 AbstractChannelHandlerContext 中的 write 方法。该方法是 writeAndFlush 的**核心逻辑**，具体见以下源码。

```java
private void write(Object msg, boolean flush, ChannelPromise promise) {

    // ...... 省略部分非核心代码 ......



    // 找到 Pipeline 链表中下一个 Outbound 类型的 ChannelHandler 节点

    final AbstractChannelHandlerContext next = findContextOutbound(flush ?

            (MASK_WRITE | MASK_FLUSH) : MASK_WRITE);

    final Object m = pipeline.touch(msg, next);

    EventExecutor executor = next.executor();

    // 判断当前线程是否是 NioEventLoop 中的线程

    if (executor.inEventLoop()) {

        if (flush) {

            // 因为 flush == true，所以流程走到这里

            next.invokeWriteAndFlush(m, promise);

        } else {

            next.invokeWrite(m, promise);

        }

    } else {

        final AbstractWriteTask task;

        if (flush) {

            task = WriteAndFlushTask.newInstance(next, m, promise);

        }  else {

            task = WriteTask.newInstance(next, m, promise);

        }

        if (!safeExecute(executor, task, promise, m)) {

            task.cancel();

        }

    }

}

```

首先我们确认下方法的入参，因为我们需要执行 flush 动作，所以 flush == true；write 方法还需要 ChannelPromise 参数，可见写操作是个异步的过程。AbstractChannelHandlerContext 会默认初始化一个 ChannelPromise 完成该异步操作，ChannelPromise 内部持有当前的 Channel 和 EventLoop，此外你可以向 ChannelPromise 中注册回调监听 listener 来获得异步操作的结果。

重要步骤:

1. 调用 findContextOutbound 方法找到 Pipeline 链表中下一个 Outbound 类型的 ChannelHandler。在我们模拟的场景中下一个 Outbound 节点是 ResponseSampleEncoder。
2. 通过 inEventLoop 方法判断当前线程的身份标识，如果当前线程和 EventLoop 分配给当前 Channel 的线程是同一个线程的话，那么所提交的任务将被立即执行。否则当前的操作将被封装成一个 Task 放入到 EventLoop 的任务队列，稍后执行。
3. 因为 flush== true，将会直接执行 next.invokeWriteAndFlush(m, promise) 这行代码，我们跟进去源码。发现最终会它会执行下一个 ChannelHandler 节点的 write 方法，那么流程又回到了 到 AbstractChannelHandlerContext 中重复执行 write 方法，继续寻找下一个 Outbound 节点。

```java
private void invokeWriteAndFlush(Object msg, ChannelPromise promise) {

    if (invokeHandler()) {

        invokeWrite0(msg, promise);

        invokeFlush0();

    } else {

        writeAndFlush(msg, promise);

    }

}

private void invokeWrite0(Object msg, ChannelPromise promise) {

    try {

        ((ChannelOutboundHandler) handler()).write(this, msg, promise);

    } catch (Throwable t) {

        notifyOutboundHandlerException(t, promise);

    }

}

```

### 写 Buffer 队列

Head节点的write方法源码

```java
// HeadContext # write

@Override

public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {

    unsafe.write(msg, promise);

}

// AbstractChannel # AbstractUnsafe # write

@Override

public final void write(Object msg, ChannelPromise promise) {

    assertEventLoop();

    ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;

    if (outboundBuffer == null) {

        safeSetFailure(promise, newClosedChannelException(initialCloseCause));

        ReferenceCountUtil.release(msg);

        return;

    }

    int size;

    try {

        msg = filterOutboundMessage(msg); // 过滤消息

        size = pipeline.estimatorHandle().size(msg);

        if (size < 0) {

            size = 0;

        }

    } catch (Throwable t) {

        safeSetFailure(promise, t);

        ReferenceCountUtil.release(msg);

        return;

    }

    outboundBuffer.addMessage(msg, size, promise); // 向 Buffer 中添加数据

}

```

可以看出 Head 节点是通过调用 unsafe 对象完成数据写入的，unsafe 对应的是 NioSocketChannelUnsafe 对象实例，最终调用到 AbstractChannel 中的 write 方法，该方法有两个重要的点需要指出：

1. filterOutboundMessage 方法会对待写入的 msg 进行过滤，如果 msg 使用的不是 DirectByteBuf，那么它会将 msg 转换成 DirectByteBuf。
2. ChannelOutboundBuffer 可以理解为一个缓存结构，从源码最后一行 outboundBuffer.addMessage 可以看出是在向这个缓存中添加数据，所以 ChannelOutboundBuffer 才是理解数据发送的关键。

writeAndFlush 主要分为两个步骤，write 和 flush。通过上面的分析可以看出只调用 write 方法，数据并不会被真正发送出去，而是存储在 ChannelOutboundBuffer 的缓存内。

ChannelOutboundBuffer 的内部构造，跟进一下 addMessage 的源码：

```java
public void addMessage(Object msg, int size, ChannelPromise promise) {

    Entry entry = Entry.newInstance(msg, size, total(msg), promise);

    if (tailEntry == null) {

        flushedEntry = null;

    } else {

        Entry tail = tailEntry;

        tail.next = entry;

    }

    tailEntry = entry;

    if (unflushedEntry == null) {

        unflushedEntry = entry;

    }

    incrementPendingOutboundBytes(entry.pendingSize, false);

}

```

ChannelOutboundBuffer 缓存是一个链表结构，每次传入的数据都会被封装成一个 Entry 对象添加到链表中。ChannelOutboundBuffer 包含**三个非常重要的指针**：

1. 第一个被写到缓冲区的**节点 flushedEntry**
2. 第一个未被写到缓冲区的**节点 unflushedEntry**
3. 最后一个**节点 tailEntry**

![](https://www.hualigs.cn/image/610aa2b91146c.jpg)



第一次调用 write，因为链表里只有一个数据，所以 unflushedEntry 和 tailEntry 指针都指向第一个添加的数据 msg1。flushedEntry 指针在没有触发 flush 动作时会一直指向 NULL。

第二次调用 write，tailEntry 指针会指向新加入的 msg2，unflushedEntry 保持不变。

第 N 次调用 write，tailEntry 指针会不断指向新加入的 msgN，unflushedEntry 依然保持不变，unflushedEntry 和 tailEntry 指针之间的数据都是未写入 Socket 缓冲区的。

addMessage 方法中每次写入数据后都会调用 incrementPendingOutboundBytes 方法判断缓存的水位线，具体源码如下。

```java
private static final int DEFAULT_LOW_WATER_MARK = 32 * 1024;

private static final int DEFAULT_HIGH_WATER_MARK = 64 * 1024;

private void incrementPendingOutboundBytes(long size, boolean invokeLater) {

    if (size == 0) {

        return;

    }



    long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, size);

    // 判断缓存大小是否超过高水位线

    if (newWriteBufferSize > channel.config().getWriteBufferHighWaterMark()) {

        setUnwritable(invokeLater);

    }

}

```

incrementPendingOutboundBytes 的逻辑非常简单，每次添加数据时都会累加数据的字节数，然后判断缓存大小是否超过所设置的高水位线 64KB，如果超过了高水位，那么 Channel 会被设置为不可写状态。直到缓存的数据大小低于低水位线 32KB 以后，Channel 才恢复成可写状态。

### 刷新 Buffer 队列

当执行完 write 写操作之后，invokeFlush0 会触发 flush 动作，与 write 方法类似，flush 方法同样会从 Tail 节点开始传播到 Head 节点，同样我们跟进下 HeadContext 的 flush 源码：

```java
// HeadContext # flush

@Override

public void flush(ChannelHandlerContext ctx) {

    unsafe.flush();

}

// AbstractChannel # flush

@Override

public final void flush() {

    assertEventLoop();

    ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;

    if (outboundBuffer == null) {

        return;

    }

    outboundBuffer.addFlush();

    flush0();

}

```

可以看出 flush 的核心逻辑主要分为两个步骤：addFlush 和 flush0，下面我们逐一对它们进行分析。

首先看下 addFlush 方法的源码：

```java
// ChannelOutboundBuffer # addFlush

public void addFlush() {

    Entry entry = unflushedEntry;

    if (entry != null) {

        if (flushedEntry == null) {

            flushedEntry = entry;

        }

        do {

            flushed ++;

            if (!entry.promise.setUncancellable()) {

                int pending = entry.cancel();

                // 减去待发送的数据，如果总字节数低于低水位，那么 Channel 将变为可写状态

                decrementPendingOutboundBytes(pending, false, true);

            }

            entry = entry.next;

        } while (entry != null);

        unflushedEntry = null;

    }

}

```

首次调用时 flushedEntry 指针有所改变，变更为 unflushedEntry 指针所指向的数据，然后不断循环直到 unflushedEntry 指针指向 NULL，flushedEntry 指针指向的数据才会被真正发送到 Socket 缓冲区。

在 addFlush 源码中 decrementPendingOutboundBytes 与之前 addMessage 源码中的 incrementPendingOutboundBytes 是相对应的。decrementPendingOutboundBytes 主要作用是减去待发送的数据字节，如果缓存的大小已经小于低水位，那么 Channel 会恢复为可写状态。



 flush0 的源码，定位出 flush0 的核心调用链路：

```java
// AbstractNioUnsafe # flush0

@Override

protected final void flush0() {

    if (!isFlushPending()) {

        super.flush0();

    }

}

// AbstractNioByteChannel # doWrite

@Override

protected void doWrite(ChannelOutboundBuffer in) throws Exception {

    int writeSpinCount = config().getWriteSpinCount();

    do {

        Object msg = in.current();

        if (msg == null) {

            clearOpWrite();

            return;

        }

        writeSpinCount -= doWriteInternal(in, msg);

    } while (writeSpinCount > 0);

    incompleteWrite(writeSpinCount < 0);

}

```

实际 flush0 的调用层次很深，但其实核心的逻辑在于 AbstractNioByteChannel 的 doWrite 方法，该方法负责将数据真正写入到 Socket 缓冲区。doWrite 方法的处理流程主要分为三步：

第一，根据配置获取自旋锁的次数 writeSpinCount。那么你的疑问就来了，这个自旋锁的次数主要是用来干什么的呢？当我们向 Socket 底层写数据的时候，如果每次要写入的数据量很大，是不可能一次将数据写完的，所以只能分批写入。Netty 在不断调用执行写入逻辑的时候，EventLoop 线程可能一直在等待，这样有可能会阻塞其他事件处理。所以这里自旋锁的次数相当于控制一次写入数据的最大的循环执行次数，如果超过所设置的自旋锁次数，那么写操作将会被暂时中断。

第二，根据自旋锁次数重复调用 doWriteInternal 方法发送数据，每成功发送一次数据，自旋锁的次数 writeSpinCount 减 1，当 writeSpinCount 耗尽，那么 doWrite 操作将会被暂时中断。doWriteInternal 的源码涉及 JDK NIO 底层，在这里我们不再深入展开，它的主要作用在于删除缓存中的链表节点以及调用底层 API 发送数据。

第三，调用 incompleteWrite 方法确保数据能够全部发送出去，因为自旋锁次数的限制，可能数据并没有写完，所以需要继续 OP_WRITE 事件；如果数据已经写完，清除 OP_WRITE 事件即可。



### WriteAndFlush时序图

![](https://www.hualigs.cn/image/610aa593d5676.jpg)
