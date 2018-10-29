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

import java.io.Writer;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.server.http2.PushBuilder;

/**
 * The {@link HttpHandler} to serve HTML page referencing 48 smiley images.
 * When requesting this page user may specify the smiley image size: 16 and 64;
 * and specify if HTTP/2 Push has to be used to send smiley images before
 * the client explicitly request them.
 * 
 * In order to specify the image size the "size" parameter has to be used
 * (supported values are 16 and 64).
 * In order to enable/disable HTTP/2 Push the "push" parameter has to be
 * used (supported values are true, false).
 * 
 * For example to request smileys with size 64x64 using HTTP/2 Push mode, the
 * request should look like: https://localhost:8080/getsmileys?size=64&push=true
 * 
 * @author Alexey Stashok
 */
public class SmileysHandler extends HttpHandler {

    /**
     * {@inheritDoc}
     */
    @Override
    public void service(Request request, Response response) throws Exception {
        
        // check the image size parameter
        final String sizeParamValue = request.getParameter("size");
        if (sizeParamValue == null) {
            response.sendError(400, "The size parameter is not specified");
            return;
        }
        
        // parse the image size
        final int iconSize;
        try {
            iconSize = Integer.parseInt(sizeParamValue);
        } catch (NumberFormatException e) {
            response.sendError(400, "The size parameter must be an integer value");
            return;
        }
        
        // find the smileys folder with the given size
        final String folderName = iconSize + "x" + iconSize;

        
        final boolean isPush = Boolean.valueOf(request.getParameter("push"));
        
        final int smileysPerRow = 4;
        final int totalSmileys = 48;

        // check if HTTP/2 Push is requested by the client and is enabled
        if (isPush) {
            final PushBuilder pushBuilder = request.newPushBuilder();
            
            // if psuBuilder == null - then it's not an HTTP/2 request or Push is disabled.
            if (pushBuilder != null) {

                for (int i = 0; i < totalSmileys; i++) {
                    pushBuilder.path('/' + folderName + '/' + (i + 1) + ".png");
                    pushBuilder.push();
                }
            }
        }
        
        // Compose the main HTML page
        response.setContentType("text/html");
        final Writer writer = response.getWriter();
        writer.write("<head><title>Grizzly HTTP/2 sample</title></head>");
        writer.write("<body>");
        writer.write("<table border=\"0\" align=\"center\" width=\"50%\">");
        
        int n = 1;
        for (int i = 0; i < totalSmileys / smileysPerRow; i++) {
            writer.write("<tr>");
            for (int j = 0; j < smileysPerRow; j++) {
                writer.write("<td>");
                writer.write("<img src=\"" + folderName + "/" + n++ + ".png\" alt=\"\" />");
                writer.write("</td>");
            }
            
            writer.write("</tr>");
        }
        
        writer.write("</table>");
        writer.write("</body>");
    }
}
