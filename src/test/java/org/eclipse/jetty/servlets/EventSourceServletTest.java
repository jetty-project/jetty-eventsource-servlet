package org.eclipse.jetty.servlets;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class EventSourceServletTest
{
    private Server server;
    private Connector connector;
    private ServletContextHandler context;

    @Before
    public void startServer() throws Exception
    {
        server = new Server();
        connector = new SelectChannelConnector();
        server.addConnector(connector);

        String contextPath = "/test";
        context = new ServletContextHandler(server, contextPath, ServletContextHandler.SESSIONS);
        server.start();
    }

    @After
    public void stopServer() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void testBasicFunctionality() throws Exception
    {
        final AtomicReference<EventSource.Emitter> emitterRef = new AtomicReference<EventSource.Emitter>();
        final CountDownLatch emitterLatch = new CountDownLatch(1);
        final CountDownLatch closeLatch = new CountDownLatch(1);
        class S extends EventSourceServlet
        {
            @Override
            protected EventSource newEventSource(HttpServletRequest request)
            {
                return new EventSource()
                {
                    public void onOpen(Emitter emitter) throws IOException
                    {
                        emitterRef.set(emitter);
                        emitterLatch.countDown();
                    }

                    public void onClose()
                    {
                        closeLatch.countDown();
                    }
                };
            }
        }

        String servletPath = "/eventsource";
        ServletHolder servletHolder = new ServletHolder(new S());
        int heartBeatPeriod = 2;
        servletHolder.setInitParameter("heartBeatPeriod", String.valueOf(heartBeatPeriod));
        context.addServlet(servletHolder, servletPath);

        int serverPort = connector.getLocalPort();
        Socket socket = new Socket("localhost", serverPort);
        OutputStream output = socket.getOutputStream();

        String handshake = "";
        handshake += "GET " + context.getContextPath() + servletPath + " HTTP/1.1\r\n";
        handshake += "Host: localhost:" + serverPort + "\r\n";
        handshake += "Accept: text/event-stream\r\n";
        handshake += "\r\n";
        output.write(handshake.getBytes("UTF-8"));
        output.flush();

        // Read and discard the HTTP response
        InputStream input = socket.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        String line = reader.readLine();
        while (line != null)
        {
            if (line.length() == 0)
                break;
            line = reader.readLine();
        }
        // Now we can parse the event-source stream

        Assert.assertTrue(emitterLatch.await(1, TimeUnit.SECONDS));
        EventSource.Emitter emitter = emitterRef.get();
        Assert.assertNotNull(emitter);

        String data = "foo";
        emitter.data(data);

        line = reader.readLine();
        String received = "";
        while (line != null)
        {
            received += line;
            if (line.length() == 0)
                break;
            line = reader.readLine();
        }

        Assert.assertEquals("data: " + data, received);

        socket.close();
        Assert.assertTrue(closeLatch.await(heartBeatPeriod * 2, TimeUnit.SECONDS));
    }

    @Test
    public void testServerSideClose() throws Exception
    {
        final AtomicReference<EventSource.Emitter> emitterRef = new AtomicReference<EventSource.Emitter>();
        final CountDownLatch emitterLatch = new CountDownLatch(1);
        class S extends EventSourceServlet
        {
            @Override
            protected EventSource newEventSource(HttpServletRequest request)
            {
                return new EventSource()
                {
                    public void onOpen(Emitter emitter) throws IOException
                    {
                        emitterRef.set(emitter);
                        emitterLatch.countDown();
                    }

                    public void onClose()
                    {
                    }
                };
            }
        }

        String servletPath = "/eventsource";
        ServletHolder servletHolder = new ServletHolder(new S());
        int heartBeatPeriod = 2;
        servletHolder.setInitParameter("heartBeatPeriod", String.valueOf(heartBeatPeriod));
        context.addServlet(servletHolder, servletPath);

        int serverPort = connector.getLocalPort();
        Socket socket = new Socket("localhost", serverPort);
        OutputStream output = socket.getOutputStream();

        String handshake = "";
        handshake += "GET " + context.getContextPath() + servletPath + " HTTP/1.1\r\n";
        handshake += "Host: localhost:" + serverPort + "\r\n";
        handshake += "Accept: text/event-stream\r\n";
        handshake += "\r\n";
        output.write(handshake.getBytes("UTF-8"));
        output.flush();

        // Read and discard the HTTP response
        InputStream input = socket.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        String line = reader.readLine();
        while (line != null)
        {
            if (line.length() == 0)
                break;
            line = reader.readLine();
        }
        // Now we can parse the event-source stream

        Assert.assertTrue(emitterLatch.await(1, TimeUnit.SECONDS));
        EventSource.Emitter emitter = emitterRef.get();
        Assert.assertNotNull(emitter);

        String comment = "foo";
        emitter.comment(comment);

        line = reader.readLine();
        String received = "";
        while (line != null)
        {
            received += line;
            if (line.length() == 0)
                break;
            line = reader.readLine();
        }

        Assert.assertEquals(": " + comment, received);

        emitter.close();

        line = reader.readLine();
        Assert.assertNull(line);

        socket.close();
    }

    @Test
    public void testEncoding() throws Exception
    {
        // The EURO symbol
        final String data = "\u20AC";
        class S extends EventSourceServlet
        {
            @Override
            protected EventSource newEventSource(HttpServletRequest request)
            {
                return new EventSource()
                {
                    public void onOpen(Emitter emitter) throws IOException
                    {
                        emitter.data(data);
                    }

                    public void onClose()
                    {
                    }
                };
            }
        }

        String servletPath = "/eventsource";
        ServletHolder servletHolder = new ServletHolder(new S());
        int heartBeatPeriod = 2;
        servletHolder.setInitParameter("heartBeatPeriod", String.valueOf(heartBeatPeriod));
        context.addServlet(servletHolder, servletPath);

        int serverPort = connector.getLocalPort();
        Socket socket = new Socket("localhost", serverPort);
        OutputStream output = socket.getOutputStream();

        String handshake = "";
        handshake += "GET " + context.getContextPath() + servletPath + " HTTP/1.1\r\n";
        handshake += "Host: localhost:" + serverPort + "\r\n";
        handshake += "Accept: text/event-stream\r\n";
        handshake += "\r\n";
        output.write(handshake.getBytes("UTF-8"));
        output.flush();

        // Read and discard the HTTP response
        InputStream input = socket.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        String line = reader.readLine();
        while (line != null)
        {
            if (line.length() == 0)
                break;
            line = reader.readLine();
        }
        // Now we can parse the event-source stream

        line = reader.readLine();
        String received = "";
        while (line != null)
        {
            received += line;
            if (line.length() == 0)
                break;
            line = reader.readLine();
        }

        Assert.assertEquals("data: " + data, received);

        socket.close();
    }
}
