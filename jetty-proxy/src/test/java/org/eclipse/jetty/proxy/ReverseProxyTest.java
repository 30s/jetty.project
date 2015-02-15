//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.proxy;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ReverseProxyTest
{
    @Rule
    public final TestTracker tracker = new TestTracker();
    private HttpClient client;
    private Server proxy;
    private ServerConnector proxyConnector;
    private Server server;
    private ServerConnector serverConnector;

    private void startProxy() throws Exception
    {
        proxy = new Server();

        HttpConfiguration configuration = new HttpConfiguration();
        configuration.setSendDateHeader(false);
        configuration.setSendServerVersion(false);
        proxyConnector = new ServerConnector(proxy, new HttpConnectionFactory(configuration));
        proxy.addConnector(proxyConnector);

        ServletContextHandler proxyContext = new ServletContextHandler(proxy, "/", true, false);
        ServletHolder proxyServletHolder = new ServletHolder(new AsyncMiddleManServlet()
        {
            @Override
            protected String rewriteTarget(HttpServletRequest clientRequest)
            {
                StringBuilder builder = new StringBuilder();
                builder.append(clientRequest.getScheme()).append("://localhost:");
                builder.append(serverConnector.getLocalPort());
                builder.append(clientRequest.getRequestURI());
                String query = clientRequest.getQueryString();
                if (query != null)
                    builder.append("?").append(query);
                return builder.toString();
            }
        });
        proxyContext.addServlet(proxyServletHolder, "/*");

        proxy.start();
    }

    private void startServer(HttpServlet servlet) throws Exception
    {
        server = new Server();

        serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);

        ServletContextHandler appCtx = new ServletContextHandler(server, "/", true, false);
        ServletHolder appServletHolder = new ServletHolder(servlet);
        appCtx.addServlet(appServletHolder, "/*");

        server.start();
    }

    private void startClient() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @After
    public void dispose() throws Exception
    {
        client.stop();
        proxy.stop();
        server.stop();
    }

    @Test
    public void testClientHostHeaderUpdatedWhenSentToServer() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Assert.assertEquals(request.getServerPort(), serverConnector.getLocalPort());
            }
        });
        startProxy();
        startClient();

        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort()).send();
        Assert.assertEquals(200, response.getStatus());
    }
}