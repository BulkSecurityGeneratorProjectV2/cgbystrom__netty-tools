package se.cgbystrom.netty;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelException;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.junit.Test;
import se.cgbystrom.netty.http.BandwidthMeterHandler;
import se.cgbystrom.netty.http.CacheHandler;
import se.cgbystrom.netty.http.FileServerHandler;
import se.cgbystrom.netty.http.SimpleResponseHandler;
import se.cgbystrom.netty.http.router.RouterHandler;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class HttpTest {
    private int port;

    @Test
    public void serveFromFileSystem() throws IOException, InterruptedException {
        final String content = "Testing the file system";
        File f = createTemporaryFile(content);
        startServer(new ChunkedWriteHandler(), new FileServerHandler(f.getParent()));
        Thread.sleep(1000);

        assertEquals(content, get("/" + f.getName()));
    }

    @Test
    public void serveFromClassPath() throws IOException, InterruptedException {
        startServer(new ChunkedWriteHandler(), new FileServerHandler("classpath:///"));
        Thread.sleep(1000);

        assertEquals("Testing the class path", get("/test.txt"));
    }

    @Test
    public void cacheMaxAge() throws IOException, InterruptedException {
        final String content = "Testing the file system";
        File f = createTemporaryFile(content);
        startServer(new CacheHandler(), new ChunkedWriteHandler(), new FileServerHandler(f.getParent(), 100));
        Thread.sleep(1000);

        assertEquals(content, get("/" + f.getName()));
        assertTrue(f.delete());
        assertEquals(content, get("/" + f.getName()));
    }

    @Test
    public void cacheMaxAgeExpire() throws IOException, InterruptedException {
        final String content = "Testing the file system";
        File f = createTemporaryFile(content);
        startServer(new CacheHandler(), new ChunkedWriteHandler(), new FileServerHandler(f.getParent(), 1));
        Thread.sleep(1000);

        assertEquals(content, get("/" + f.getName()));
        Thread.sleep(2000);
        assertTrue(f.delete());
        get("/" + f.getName(), 404);
    }

    @Test
    public void router() throws Exception {
        final String startsWith = "startsWith:/hello-world";
        final String endsWith = "endsWith:/the-very-end";
        final String equals = "equals:/perfect-match";
        Map<String, ChannelHandler> routes = new HashMap<String, ChannelHandler>();
        routes.put(startsWith, new SimpleResponseHandler(startsWith));
        routes.put(endsWith, new SimpleResponseHandler(endsWith));
        routes.put(equals, new SimpleResponseHandler(equals));

        startServer(new ChunkedWriteHandler(), new RouterHandler(routes));

        assertEquals(startsWith, get("/hello-world/not-used"));
        assertEquals(endsWith, get("/blah-blah/blah/the-very-end"));
        assertEquals(equals, get("/perfect-match"));
        assertFalse(equals.equals(get("/perfect-match/test", 404)));
        assertEquals("Not found", get("/not-found", 404));
    }

    @Test
    public void bandwidthMeter() throws Exception {
        final String data = "Bandwidth metering!";
        final BandwidthMeterHandler meter = new BandwidthMeterHandler();

        PipelineFactory pf = new PipelineFactory(new ChunkedWriteHandler(), new SimpleResponseHandler(data));
        pf.setFirst(meter);
        startServer(pf);

        assertEquals(data, get("/bandwidth"));
        assertEquals(79, meter.getBytesSent());
        assertEquals(94, meter.getBytesReceived());

        meter.reset();
        assertEquals(0, meter.getBytesSent());
        assertEquals(0, meter.getBytesReceived());
    }


    private File createTemporaryFile(String content) throws IOException {
        File f = File.createTempFile("FileServerTest", null);
        f.deleteOnExit();
        BufferedWriter out = new BufferedWriter(new FileWriter(f));
        out.write(content);
        out.close();
        return f;
    }

    private String get(String uri) throws IOException {
        return get(uri, 200);
    }

    private String get(String uri, int expectedStatusCode) throws IOException {
        HttpClient client = new HttpClient();
        GetMethod method = new GetMethod("http://localhost:" + port + uri);

        assertEquals(expectedStatusCode, client.executeMethod(method));
        return new String(method.getResponseBody());
    }

    private int startServer(ChannelHandler... handlers) {
        return startServer(new PipelineFactory(handlers));
    }

    private int startServer(ChannelPipelineFactory factory) {
        ServerBootstrap bootstrap = new ServerBootstrap(
                new NioServerSocketChannelFactory(
                        Executors.newCachedThreadPool(),
                        Executors.newCachedThreadPool()));

        bootstrap.setPipelineFactory(factory);

        port = bindBootstrap(bootstrap, 0);
        return port;
    }

    private int bindBootstrap(ServerBootstrap bootstrap, int retryCount) {
        try {
            bootstrap.bind(new InetSocketAddress(18080 + retryCount));
        } catch (ChannelException e) {
            retryCount++;
            if (retryCount < 100) {
               return bindBootstrap(bootstrap, retryCount);
            }
        }

        return 18080 + retryCount;
    }

    public static class PipelineFactory implements ChannelPipelineFactory {
        private ChannelHandler[] handlers;
        private ChannelHandler first = null;

        public PipelineFactory(ChannelHandler... handlers) {
            this.handlers = handlers;
        }

        public void setFirst(ChannelHandler first) {
            this.first = first;
        }

        public ChannelPipeline getPipeline() throws Exception {
            ChannelPipeline pipeline = Channels.pipeline();

            if (first != null) {
                pipeline.addLast("first", first);
            }
            pipeline.addLast("decoder", new HttpRequestDecoder());
            pipeline.addLast("aggregator", new HttpChunkAggregator(65536));
            pipeline.addLast("encoder", new HttpResponseEncoder());

            for (ChannelHandler handler : handlers) {
                pipeline.addLast("handler_" + handler.toString(), handler);
            }

            return pipeline;
        }
    }
}
