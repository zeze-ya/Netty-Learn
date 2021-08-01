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



### 写 Buffer 队列

### 刷新 Buffer 队列

