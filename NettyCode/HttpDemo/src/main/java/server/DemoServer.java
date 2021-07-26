package server;

import handler.SampleInBoundHandler;
import handler.SampleOutBoundHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;

/**
 * @author Naive
 * @date 2021-07-25 17:28
 */
public class DemoServer {

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
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline()
                                    .addLast(new SampleInBoundHandler("SimpleInBoundHandlerA",false))
                                    .addLast(new SampleInBoundHandler("SimpleInBoundHandlerB",false))
                                    .addLast(new SampleInBoundHandler("SimpleInBoundHandlerC",true));
                            ch.pipeline()
                                    .addLast(new SampleOutBoundHandler("SampleOutBoundHandlerA"))
                                    .addLast(new SampleOutBoundHandler("SampleOutBoundHandlerB"))
                                    .addLast(new SampleOutBoundHandler("SampleOutBoundHandlerC"));
                        }
                    }).childOption(ChannelOption.SO_KEEPALIVE,true);

            ChannelFuture f = serverBootstrap.bind().sync();

            System.out.println("测试服务启动...端口："+port);
            f.channel().closeFuture().sync();
            System.out.println("阻塞结束，退出");
        }finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }

    }


    public static void main(String[] args) throws InterruptedException {
        new DemoServer().start(1111);
    }

}
