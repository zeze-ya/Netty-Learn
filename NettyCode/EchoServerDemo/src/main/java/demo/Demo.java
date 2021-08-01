package demo;

import server.EchoServer;

/**
 * @author Naive
 * @date 2021-08-01 20:24
 */
public class Demo {
    public static void main(String[] args) throws InterruptedException {
        new EchoServer().startEchoServer(9999);
    }
}
