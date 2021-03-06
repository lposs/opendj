/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2006-2010 Sun Microsystems, Inc.
 * Portions Copyright 2014-2016 ForgeRock AS.
 */
package org.opends.server.protocols.ldap;

import static org.opends.messages.ProtocolMessages.*;
import static org.opends.server.loggers.AccessLogger.logConnect;
import static org.opends.server.util.StaticUtils.*;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.i18n.slf4j.LocalizedLogger;
import org.forgerock.opendj.io.ASN1Reader;
import org.forgerock.opendj.ldap.DecodeException;
import org.opends.server.api.DirectoryThread;
import org.opends.server.api.ServerShutdownListener;
import org.opends.server.types.DisconnectReason;
import org.opends.server.types.InitializationException;
import org.opends.server.types.LDAPException;

/**
 * This class defines an LDAP request handler, which is associated with an LDAP
 * connection handler and is responsible for reading and decoding any requests
 * that LDAP clients may send to the server.  Multiple request handlers may be
 * used in conjunction with a single connection handler for better performance
 * and scalability.
 */
public class LDAPRequestHandler
       extends DirectoryThread
       implements ServerShutdownListener
{
  private static final LocalizedLogger logger = LocalizedLogger.getLoggerForThisClass();

  /** Indicates whether the Directory Server is in the process of shutting down. */
  private volatile boolean shutdownRequested;
  /** The current set of selection keys. */
  private volatile SelectionKey[] keys = new SelectionKey[0];

  /**
   * The queue that will be used to hold the set of pending connections that
   * need to be registered with the selector.
   * TODO: revisit, see Issue 4202.
   */
  private List<LDAPClientConnection> pendingConnections = new LinkedList<>();

  /** Lock object for synchronizing access to the pending connections queue. */
  private final Object pendingConnectionsLock = new Object();
  /** The list of connections ready for request processing. */
  private final LinkedList<LDAPClientConnection> readyConnections = new LinkedList<>();
  /** The selector that will be used to monitor the client connections. */
  private final Selector selector;
  /** The name to use for this request handler. */
  private final String handlerName;



  /**
   * Creates a new LDAP request handler that will be associated with the
   * provided connection handler.
   *
   * @param  connectionHandler  The LDAP connection handler with which this
   *                            request handler is associated.
   * @param  requestHandlerID   The integer value that may be used to distinguish
   *                            this request handler from others associated with
   *                            the same connection handler.
   * @throws  InitializationException  If a problem occurs while initializing
   *                                   this request handler.
   */
  public LDAPRequestHandler(LDAPConnectionHandler connectionHandler,
                            int requestHandlerID)
         throws InitializationException
  {
    super("LDAP Request Handler " + requestHandlerID +
          " for connection handler " + connectionHandler);


    handlerName        = getName();

    try
    {
      selector = Selector.open();
    }
    catch (Exception e)
    {
      logger.traceException(e);

      LocalizableMessage message = ERR_LDAP_REQHANDLER_OPEN_SELECTOR_FAILED.get(handlerName, e);
      throw new InitializationException(message, e);
    }

    try
    {
      // Check to see if we get an error while trying to perform a select.  If
      // we do, then it's likely CR 6322825 and the server won't be able to
      // handle LDAP requests in its current state.
      selector.selectNow();
    }
    catch (IOException ioe)
    {
      StackTraceElement[] stackElements = ioe.getStackTrace();
      if (stackElements != null && stackElements.length > 0)
      {
        StackTraceElement ste = stackElements[0];
        if (ste.getClassName().equals("sun.nio.ch.DevPollArrayWrapper")
            && ste.getMethodName().contains("poll")
            && ioe.getMessage().equalsIgnoreCase("Invalid argument"))
        {
          LocalizableMessage message = ERR_LDAP_REQHANDLER_DETECTED_JVM_ISSUE_CR6322825.get(ioe);
          throw new InitializationException(message, ioe);
        }
      }
    }
  }



  /**
   * Operates in a loop, waiting for client requests to arrive and ensuring that
   * they are processed properly.
   */
  @Override
  public void run()
  {
    // Operate in a loop until the server shuts down.  Each time through the
    // loop, check for new requests, then check for new connections.
    while (!shutdownRequested)
    {
      LDAPClientConnection readyConnection = null;
      while ((readyConnection = readyConnections.poll()) != null)
      {
        try
        {
          ASN1Reader asn1Reader = readyConnection.getASN1Reader();
          boolean ldapMessageProcessed = false;
          while (true)
          {
            if (asn1Reader.elementAvailable())
            {
              if (!ldapMessageProcessed)
              {
                if (readyConnection.processLDAPMessage(
                    LDAPReader.readMessage(asn1Reader)))
                {
                  ldapMessageProcessed = true;
                }
                else
                {
                  break;
                }
              }
              else
              {
                readyConnections.add(readyConnection);
                break;
              }
            }
            else
            {
              if (readyConnection.processDataRead() <= 0)
              {
                break;
              }
            }
          }
        }
        catch (DecodeException | LDAPException e)
        {
          logger.traceException(e);
          readyConnection.disconnect(DisconnectReason.PROTOCOL_ERROR, true,
            e.getMessageObject());
        }
        catch (Exception e)
        {
          logger.traceException(e);
          readyConnection.disconnect(DisconnectReason.PROTOCOL_ERROR, true,
            LocalizableMessage.raw(e.toString()));
        }
      }

      // Check to see if we have any pending connections that need to be
      // registered with the selector.
      List<LDAPClientConnection> tmp = null;
      synchronized (pendingConnectionsLock)
      {
        if (!pendingConnections.isEmpty())
        {
          tmp = pendingConnections;
          pendingConnections = new LinkedList<>();
        }
      }

      if (tmp != null)
      {
        for (LDAPClientConnection c : tmp)
        {
          try
          {
            SocketChannel socketChannel = c.getSocketChannel();
            socketChannel.configureBlocking(false);
            socketChannel.register(selector, SelectionKey.OP_READ, c);
            logConnect(c);
          }
          catch (Exception e)
          {
            logger.traceException(e);

            c.disconnect(DisconnectReason.SERVER_ERROR, true,
                ERR_LDAP_REQHANDLER_CANNOT_REGISTER.get(handlerName, e));
          }
        }
      }

      // Create a copy of the selection keys which can be used in a
      // thread-safe manner by getClientConnections. This copy is only
      // updated once per loop, so may not be accurate.
      keys = selector.keys().toArray(new SelectionKey[0]);

      int selectedKeys = 0;
      try
      {
        // We timeout every second so that we can refresh the key list.
        selectedKeys = selector.select(1000);
      }
      catch (Exception e)
      {
        logger.traceException(e);

        // FIXME -- Should we do something else with this?
      }

      if (shutdownRequested)
      {
        // Avoid further processing and disconnect all clients.
        break;
      }

      if (selectedKeys > 0)
      {
        Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
        while (iterator.hasNext())
        {
          SelectionKey key = iterator.next();

          try
          {
            if (key.isReadable())
            {
              LDAPClientConnection clientConnection = null;

              try
              {
                clientConnection = (LDAPClientConnection) key.attachment();

                int readResult = clientConnection.processDataRead();
                if (readResult < 0)
                {
                  key.cancel();
                }
                if (readResult > 0) {
                  readyConnections.add(clientConnection);
                }
              }
              catch (Exception e)
              {
                logger.traceException(e);

                // We got some other kind of error.  If nothing else, cancel the
                // key, but if the client connection is available then
                // disconnect it as well.
                key.cancel();

                if (clientConnection != null)
                {
                  clientConnection.disconnect(DisconnectReason.SERVER_ERROR, false,
                      ERR_UNEXPECTED_EXCEPTION_ON_CLIENT_CONNECTION.get(getExceptionMessage(e)));
                }
              }
            }
            else if (! key.isValid())
            {
              key.cancel();
            }
          }
          catch (CancelledKeyException cke)
          {
            logger.traceException(cke);

            // This could happen if a connection was closed between the time
            // that select returned and the time that we try to access the
            // associated channel.  If that was the case, we don't need to do
            // anything.
          }
          catch (Exception e)
          {
            logger.traceException(e);

            // This should not happen, and it would have caused our reader
            // thread to die.  Log a severe error.
            logger.error(ERR_LDAP_REQHANDLER_UNEXPECTED_SELECT_EXCEPTION, getName(), getExceptionMessage(e));
          }
          finally
          {
            if (!key.isValid())
            {
              // Help GC - release the connection.
              key.attach(null);
            }

            iterator.remove();
          }
        }
      }
    }

    // Disconnect all active connections.
    SelectionKey[] keyArray = selector.keys().toArray(new SelectionKey[0]);
    for (SelectionKey key : keyArray)
    {
      LDAPClientConnection c = (LDAPClientConnection) key.attachment();

      try
      {
        key.channel().close();
      }
      catch (Exception e)
      {
        logger.traceException(e);
      }

      try
      {
        key.cancel();
      }
      catch (Exception e)
      {
        logger.traceException(e);
      }

      try
      {
        c.disconnect(DisconnectReason.SERVER_SHUTDOWN, true,
            ERR_LDAP_REQHANDLER_DEREGISTER_DUE_TO_SHUTDOWN.get());
      }
      catch (Exception e)
      {
        logger.traceException(e);
      }
    }

    // Disconnect all pending connections.
    synchronized (pendingConnectionsLock)
    {
      for (LDAPClientConnection c : pendingConnections)
      {
        try
        {
          c.disconnect(DisconnectReason.SERVER_SHUTDOWN, true,
              ERR_LDAP_REQHANDLER_DEREGISTER_DUE_TO_SHUTDOWN.get());
        }
        catch (Exception e)
        {
          logger.traceException(e);
        }
      }
    }
  }



  /**
   * Registers the provided client connection with this request
   * handler so that any requests received from that client will be
   * processed.
   *
   * @param clientConnection
   *          The client connection to be registered with this request
   *          handler.
   * @return <CODE>true</CODE> if the client connection was properly
   *         registered with this request handler, or
   *         <CODE>false</CODE> if not.
   */
  public boolean registerClient(LDAPClientConnection clientConnection)
  {
    // FIXME -- Need to check if the maximum client limit has been reached.


    // If the server is in the process of shutting down, then we don't want to
    // accept it.
    if (shutdownRequested)
    {
      clientConnection.disconnect(DisconnectReason.SERVER_SHUTDOWN, true,
           ERR_LDAP_REQHANDLER_REJECT_DUE_TO_SHUTDOWN.get());
      return false;
    }

    // Try to add the new connection to the queue.  If it succeeds, then wake
    // up the selector so it will be picked up right away.  Otherwise,
    // disconnect the client.
    synchronized (pendingConnectionsLock)
    {
      pendingConnections.add(clientConnection);
    }

    selector.wakeup();
    return true;
  }



  /**
   * Retrieves the set of all client connections that are currently registered
   * with this request handler.
   *
   * @return  The set of all client connections that are currently registered
   *          with this request handler.
   */
  public Collection<LDAPClientConnection> getClientConnections()
  {
    ArrayList<LDAPClientConnection> connList = new ArrayList<>(keys.length);
    for (SelectionKey key : keys)
    {
      LDAPClientConnection c = (LDAPClientConnection) key.attachment();

      // If the client has disconnected the attachment may be null.
      if (c != null)
      {
        connList.add(c);
      }
    }

    return connList;
  }

  @Override
  public String getShutdownListenerName()
  {
    return handlerName;
  }

  @Override
  public void processServerShutdown(LocalizableMessage reason)
  {
    shutdownRequested = true;
    selector.wakeup();
  }
}

