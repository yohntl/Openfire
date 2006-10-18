/**
 * $RCSfile$
 * $Revision: $
 * $Date: $
 *
 * Copyright (C) 2006 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Public License (GPL),
 * a copy of which is included in this distribution.
 */
package org.jivesoftware.wildfire.http;

import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.wildfire.SessionManager;
import org.jivesoftware.wildfire.StreamID;
import org.jivesoftware.wildfire.multiplex.MultiplexerPacketRouter;
import org.jivesoftware.wildfire.multiplex.UnknownStanzaException;
import org.jivesoftware.wildfire.auth.UnauthorizedException;
import org.dom4j.Element;

import java.util.*;
import java.io.UnsupportedEncodingException;

/**
 *
 */
public class HttpSessionManager {

    /**
     * Milliseconds a connection has to be idle to be closed. Default is 30 minutes. Sending
     * stanzas to the client is not considered as activity. We are only considering the connection
     * active when the client sends some data or hearbeats (i.e. whitespaces) to the server.
     * The reason for this is that sending data will fail if the connection is closed. And if
     * the thread is blocked while sending data (because the socket is closed) then the clean up
     * thread will close the socket anyway.
     */
    private static int inactivityTimeout;

    /**
     * The connection manager MAY limit the number of simultaneous requests the client makes with
     * the 'requests' attribute. The RECOMMENDED value is "2". Servers that only support polling
     * behavior MUST prevent clients from making simultaneous requests by setting the 'requests'
     * attribute to a value of "1" (however, polling is NOT RECOMMENDED). In any case, clients MUST
     * NOT make more simultaneous requests than specified by the connection manager.
     */
    private static int maxRequests;

    /**
     * The connection manager SHOULD include two additional attributes in the session creation
     * response element, specifying the shortest allowable polling interval and the longest
     * allowable inactivity period (both in seconds). Communication of these parameters enables
     * the client to engage in appropriate behavior (e.g., not sending empty request elements more
     * often than desired, and ensuring that the periods with no requests pending are
     * never too long).
     */
    private static int pollingInterval;

    private InactivityTimer timer = new InactivityTimer();
    private SessionManager sessionManager;
    private Map<String, HttpSession> sessionMap = new HashMap<String, HttpSession>();

    static {
        // Set the default read idle timeout. If none was set then assume 30 minutes
        inactivityTimeout = JiveGlobals.getIntProperty("xmpp.httpbind.client.idle", 30);
        maxRequests = JiveGlobals.getIntProperty("xmpp.httpbind.client.requests.max", 2);
        pollingInterval = JiveGlobals.getIntProperty("xmpp.httpbind.client.requests.polling", 5);
    }

    public HttpSessionManager() {
        this.sessionManager = SessionManager.getInstance();
    }

    public HttpSession getSession(String streamID) {
        return sessionMap.get(streamID);
    }

    public HttpSession createSession(Element rootNode, HttpConnection connection)
            throws UnauthorizedException
    {
        // TODO Check if IP address is allowed to connect to the server

        // Default language is English ("en").
        String language = rootNode.attributeValue("xml:lang");
        if(language == null || "".equals(language)) {
            language = "en";
        }

        int wait = getIntAttribute(rootNode.attributeValue("wait"), 60);
        int hold = getIntAttribute(rootNode.attributeValue("hold"), 1);

        HttpSession session = createSession();
        session.setWait(wait);
        session.setHold(hold);
        session.setSecure(connection.isSecure());
        session.setMaxPollingInterval(pollingInterval);
        session.setInactivityTimeout(inactivityTimeout);
        // Store language and version information in the connection.
        session.setLanaguage(language);
        try {
            connection.deliverBody(createSessionCreationResponse(session));
        }
        catch (HttpConnectionClosedException e) {
            /* This won't happen here. */
        }

        timer.reset(session);
        return session;
    }

    private HttpSession createSession() throws UnauthorizedException {
        // Create a ClientSession for this user.
        StreamID streamID = SessionManager.getInstance().nextStreamID();
        // Send to the server that a new client session has been created
        HttpSession session = sessionManager.createClientHttpSession(streamID);
        // Register that the new session is associated with the specified stream ID
        sessionMap.put(streamID.getID(), session);
        session.addSessionCloseListener(new SessionListener() {
            public void connectionOpened(HttpSession session, HttpConnection connection) {
                if (session instanceof HttpSession) {
                    timer.stop(session);
                }
            }

            public void connectionClosed(HttpSession session, HttpConnection connection) {
                if (session.getConnectionCount() <= 0) {
                    timer.reset(session);
                }
            }

            public void sessionClosed(HttpSession session) {
                sessionMap.remove(session.getStreamID());
                timer.stop(session);
            }
        });
        return session;
    }

    private static int getIntAttribute(String value, int defaultValue) {
        if(value == null || "".equals(value)) {
            return defaultValue;
        }
        try {
            return Integer.valueOf(value);
        }
        catch (Exception ex) {
            return defaultValue;
        }
    }

    private String createSessionCreationResponse(HttpSession session) {
        StringBuilder builder = new StringBuilder();
        builder.append("<body")
                .append(" xmlns='http://jabber.org/protocol/httpbind'").append(" authID='")
                .append(session.getStreamID()).append("'")
                .append(" sid='").append(session.getStreamID()).append("'")
                .append(" secure='true" + "'").append(" requests='")
                .append(String.valueOf(maxRequests)).append("'")
                .append(" inactivity='").append(String.valueOf(session.getInactivityTimeout()))
                .append("'")
                .append(" polling='").append(String.valueOf(pollingInterval)).append("'")
                .append(" wait='").append(String.valueOf(session.getWait())).append("'")
                .append(">");
        builder.append("<stream:features>");
        builder.append(session.getAvailableStreamFeatures());
        builder.append("</stream:features>");
        builder.append("</body>");

        return builder.toString();
    }

    public HttpConnection forwardRequest(long rid, HttpSession session, boolean isSecure,
                                         Element rootNode) throws HttpBindException,
            HttpConnectionClosedException
    {
        //noinspection unchecked
        List<Element> elements = rootNode.elements();
        boolean isPoll = elements.size() <= 0;
        HttpConnection connection = new HttpConnection(rid, isSecure);
        session.addConnection(connection, isPoll);
        MultiplexerPacketRouter router = new MultiplexerPacketRouter(session);

        for (Element packet : elements) {
            try {
                router.route(packet);
            }
            catch (UnsupportedEncodingException e) {
                throw new HttpBindException("Bad auth request, unknown encoding", true, 400);
            }
            catch (UnknownStanzaException e) {
                throw new HttpBindException("Unknown packet type.", false, 400);
            }
        }

        return connection;
    }

    private class InactivityTimer extends Timer {
        private Map<String, InactivityTimeoutTask> sessionMap
                = new HashMap<String, InactivityTimeoutTask>();

        public void stop(HttpSession session) {
            InactivityTimeoutTask task = sessionMap.remove(session.getStreamID());
            if(task != null) {
                task.cancel();
            }
        }

        public void reset(HttpSession session) {
            stop(session);
            if(session.isClosed()) {
                return;
            }
            InactivityTimeoutTask task = new InactivityTimeoutTask(session);
            schedule(task, session.getInactivityTimeout() * 1000);
            sessionMap.put(session.getStreamID().getID(), task);
        }
    }

    private class InactivityTimeoutTask extends TimerTask {
        private HttpSession session;

        public InactivityTimeoutTask(HttpSession session) {
            this.session = session;
        }

        public void run() {
            session.close();
        }
    }
}