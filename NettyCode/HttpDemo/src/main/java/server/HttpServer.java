package server;

import handler.HttpServerHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import java.net.InetSocketAddress;

/**
 * @author Naive
 * @date 2021-07-12 22:15
 */
public class HttpServer {

    public void start(int port) throws InterruptedException {
        NioEventLoopGroup bossGroup = new NioEventLoopGroup();
        NioEventLoopGroup workerGroup = new NioEventLoopGroup();


        try {
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            serverBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .localAddress(new InetSocketAddress(port))
                    .childHandler(new ChannelInitializer<SocketChannel>() {

                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            socketChannel.pipeline()
                                    .addLast("codec",new HttpServerCodec()) // Http编码
                                    .addLast("compressor",new HttpContentCompressor()) //http压缩
                                    .addLast("aggregator",new HttpObjectAggregator(65536)) // http聚合
                                    .addLast("handler",new HttpServerHandler());// 自定义消息处理
                        }
                    }).childOption(ChannelOption.SO_KEEPALIVE,true);

            ChannelFuture f = serverBootstrap.bind().sync();

            System.out.println("Http服务已启动...."+port);
            f.channel().closeFuture().sync();
            System.out.println("阻塞结束，我要退出了");
        }finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }


    //region

    public static void main(String[] args) throws InterruptedException {
        new HttpServer().start(555);
    }

    //endregion


}
