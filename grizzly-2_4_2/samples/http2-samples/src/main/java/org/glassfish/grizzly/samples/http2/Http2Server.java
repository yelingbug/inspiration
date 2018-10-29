/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2017 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.grizzly.samples.http2;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http2.Http2AddOn;
import org.glassfish.grizzly.http2.Http2Configuration;

/**
 * The HTTPS server initialization code.
 * 
 * The class initializes and start HTTP/2 server on port 8080.
 * The {@link SmileysHandler}, registered to process incoming requests,
 * can operate in two modes: HTTP/2 or HTTP/2+Push.
 * 
 * See {@link SmileysHandler} documentation to learn how to switch HTTP/2 and
 * HTTP/2 with push modes.
 * 
 * @see SmileysHandler
 *
 * @author Alexey Stashok
 */
public class Http2Server {
    private static final Logger LOGGER = Grizzly.logger(Http2Server.class);
    private static final String HOST = "0.0.0.0";
    private static final int PORT = 8080;
    
    public static void main(String[] args) throws IOException {

        final Http2AddOn http2AddOn =
                new Http2AddOn(Http2Configuration.builder().build());
        
        // Configure HttpServer
        final HttpServer server = ServerUtils.configureServer(
                "http2-listener", HOST, PORT, true, http2AddOn);
        try {
            // Start the server
            server.start();
            
            System.out.println("Press enter to exit...");
            //noinspection ResultOfMethodCallIgnored
            System.in.read();
        } catch (IOException ioe) {
            LOGGER.log(Level.SEVERE, ioe.toString(), ioe);
        } finally {
            // Stop the server
            server.shutdownNow();
        }
    }
}
