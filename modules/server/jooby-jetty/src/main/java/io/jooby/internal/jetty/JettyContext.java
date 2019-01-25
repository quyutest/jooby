/**
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    Copyright 2014 Edgar Espina
 */
package io.jooby.internal.jetty;

import io.jooby.*;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.MultiPartFormInputStream;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.MultiMap;
import io.jooby.Throwing;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.Part;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.Executor;

import static org.eclipse.jetty.server.Request.__MULTIPART_CONFIG_ELEMENT;

public class JettyContext implements Callback, Context {
  private final int bufferSize;
  private final long maxRequestSize;
  private Request request;
  private Response response;
  private QueryString query;
  private Formdata form;
  private Multipart multipart;
  private List<FileUpload> files;
  private Value.Object headers;
  private Map<String, String> pathMap = Collections.EMPTY_MAP;
  private Map<String, Object> locals = Collections.EMPTY_MAP;
  private Router router;
  private Route route;
  private MediaType responseType;

  public JettyContext(Request request, Router router, int bufferSize, long maxRequestSize) {
    this.request = request;
    this.response = request.getResponse();
    this.router = router;
    this.bufferSize = bufferSize;
    this.maxRequestSize = maxRequestSize;
  }

  @Nonnull @Override public Map<String, Object> attributes() {
    return locals;
  }

  @Nullable @Override public <T> T attribute(String name) {
    return (T) locals.get(name);
  }

  @Nonnull @Override public Context attribute(@Nonnull String name, @Nonnull Object value) {
    if (locals == Collections.EMPTY_MAP) {
      locals = new HashMap<>();
    }
    locals.put(name, value);
    return this;
  }

  @Override public String name() {
    return "jetty";
  }

  @Nonnull @Override public Body body() {
    try {
      InputStream in = request.getInputStream();
      long len = request.getContentLengthLong();
      if (maxRequestSize > 0) {
        in = new LimitedInputStream(in, maxRequestSize);
      }
      return Body.of(in, len);
    } catch (IOException x) {
      throw Throwing.sneakyThrow(x);
    }
  }

  @Nonnull @Override public Router router() {
    return router;
  }

  @Nonnull @Override public String method() {
    return request.getMethod().toUpperCase();
  }

  @Nonnull @Override public Route route() {
    return route;
  }

  @Nonnull @Override public Context route(Route route) {
    this.route = route;
    return this;
  }

  @Nonnull @Override public String pathString() {
    return request.getRequestURI();
  }

  @Nonnull @Override public Map<String, String> pathMap() {
    return pathMap;
  }

  @Nonnull @Override public Context pathMap(Map<String, String> pathMap) {
    this.pathMap = pathMap;
    return this;
  }

  @Nonnull @Override public QueryString query() {
    if (query == null) {
      String queryString = request.getQueryString();
      if (queryString == null) {
        query = QueryString.EMPTY;
      } else {
        query = Value.queryString('?' + queryString);
      }
    }
    return query;
  }

  @Nonnull @Override public Formdata form() {
    if (form == null) {
      form = new Formdata();
      formParam(request, form);
    }
    return form;
  }

  @Nonnull @Override public Multipart multipart() {
    if (multipart == null) {
      multipart = new Multipart();
      form = multipart;

      request.setAttribute(__MULTIPART_CONFIG_ELEMENT,
          new MultipartConfigElement(router.tmpdir().toString(), -1L, maxRequestSize, bufferSize));

      formParam(request, multipart);

      // Files:
      try {
        Collection<Part> parts = request.getParts();
        for (Part part : parts) {
          if (part.getSubmittedFileName() != null) {
            String name = part.getName();
            multipart.put(name,
                register(new JettyFileUpload(name, (MultiPartFormInputStream.MultiPart) part)));
          }
        }
      } catch (IOException | ServletException x) {
        throw Throwing.sneakyThrow(x);
      }
    }
    return multipart;
  }

  @Nonnull @Override public Value headers() {
    if (headers == null) {
      headers = Value.headers();
      Enumeration<String> names = request.getHeaderNames();
      while (names.hasMoreElements()) {
        String name = names.nextElement();
        Enumeration<String> values = request.getHeaders(name);
        while (values.hasMoreElements()) {
          headers.put(name, values.nextElement());
        }
      }
    }
    return headers;
  }

  @Nonnull @Override public String remoteAddress() {
    return request.getRemoteAddr();
  }

  @Nonnull @Override public String protocol() {
    return request.getProtocol();
  }

  @Override public boolean isInIoThread() {
    return false;
  }

  @Nonnull @Override public Context dispatch(@Nonnull Runnable action) {
    return dispatch(router.worker(), action);
  }

  @Nonnull @Override
  public Context dispatch(@Nonnull Executor executor, @Nonnull Runnable action) {
    if (router.worker() == executor) {
      action.run();
    } else {
      ifStartAsync();
      executor.execute(action);
    }
    return this;
  }

  @Nonnull @Override public Context detach(@Nonnull Runnable action) {
    ifStartAsync();
    action.run();
    return this;
  }

  @Nonnull @Override public Context statusCode(int statusCode) {
    response.setStatus(statusCode);
    return this;
  }

  @Nonnull @Override public MediaType responseType() {
    return responseType == null ? MediaType.text : responseType;
  }

  @Nonnull @Override public Context defaultResponseType(@Nonnull MediaType contentType) {
    if (responseType == null) {
      responseType(contentType, contentType.charset());
    }
    return this;
  }

  @Nonnull @Override
  public Context responseType(@Nonnull MediaType contentType, @Nullable Charset charset) {
    this.responseType = contentType;
    response.setHeader(HttpHeader.CONTENT_TYPE, contentType.toContentTypeHeader(charset));
    return this;
  }

  @Nonnull @Override public Context header(@Nonnull String name, @Nonnull String value) {
    response.setHeader(name, value);
    return this;
  }

  @Nonnull @Override public Context responseLength(long length) {
    response.setContentLengthLong(length);
    return this;
  }

  @Nonnull @Override public Sender responseSender() {
    ifSetChunked();
    ifStartAsync();
    return new JettySender(this, response.getHttpOutput());
  }

  @Nonnull @Override public OutputStream responseStream() {
    try {
      ifSetChunked();
      OutputStream outputStream = response.getOutputStream();
      return outputStream;
    } catch (IOException x) {
      throw Throwing.sneakyThrow(x);
    }
  }

  @Nonnull @Override public Writer responseWriter(MediaType type, Charset charset) {
    try {
      ifSetChunked();
      responseType(type, charset);
      PrintWriter writer = response.getWriter();
      return writer;
    } catch (IOException x) {
      throw Throwing.sneakyThrow(x);
    }
  }

  @Nonnull @Override public Context sendStatusCode(int statusCode) {
    response.setHeader(HttpHeader.TRANSFER_ENCODING, null);
    response.setLongContentLength(0);
    response.setStatus(statusCode);
    sendBytes(ByteBuffer.wrap(new byte[0]));
    return this;
  }

  @Nonnull @Override public Context sendBytes(@Nonnull byte[] data) {
    return sendBytes(ByteBuffer.wrap(data));
  }

  @Nonnull @Override public Context sendString(@Nonnull String data, @Nonnull Charset charset) {
    return sendBytes(ByteBuffer.wrap(data.getBytes(charset)));
  }

  @Nonnull @Override public Context sendBytes(@Nonnull ByteBuffer data) {
    HttpOutput sender = response.getHttpOutput();
    sender.sendContent(data, this);
    return this;
  }

  @Nonnull @Override public Context sendStream(@Nonnull InputStream in) {
    if (in instanceof FileInputStream) {
      // use channel
      return sendFile(((FileInputStream) in).getChannel());
    }
    try {
      ifStartAsync();

      long len = response.getContentLength();
      InputStream stream;
      if (len > 0) {
        ByteRange range = ByteRange.parse(request.getHeader(HttpHeader.RANGE.asString()))
            .apply(this, len);
        in.skip(range.start);
        stream = Functions.limit(in, range.end);
      } else {
        response.setHeader(HttpHeader.TRANSFER_ENCODING, HttpHeaderValue.CHUNKED.asString());
        stream = in;
      }
      response.getHttpOutput().sendContent(stream, this);
      return this;
    } catch (IOException x) {
      throw Throwing.sneakyThrow(x);
    }
  }

  @Nonnull @Override public Context sendFile(@Nonnull FileChannel file) {
    try (FileChannel channel = file) {
      response.setLongContentLength(channel.size());
      return sendStream(Channels.newInputStream(file));
    } catch (IOException x) {
      throw Throwing.sneakyThrow(x);
    }
  }

  @Override public boolean isResponseStarted() {
    return response.isCommitted();
  }

  @Override public void succeeded() {
    destroy(null);
  }

  @Override public void failed(Throwable x) {
    destroy(x);
  }

  @Override public InvocationType getInvocationType() {
    return InvocationType.NON_BLOCKING;
  }

  void destroy(Throwable x) {
    Logger log = router.log();
    if (x != null) {
      if (Server.connectionLost(x)) {
        log.debug("exception found while sending response {} {}", method(), pathString(), x);
      } else {
        log.error("exception found while sending response {} {}", method(), pathString(), x);
      }
    }
    if (files != null) {
      for (FileUpload file : files) {
        try {
          file.destroy();
        } catch (Exception e) {
          log.debug("file upload destroy resulted in exception", e);
        }
      }
      files.clear();
      files = null;
    }
    try {
      if (request.isAsyncStarted()) {
        request.getAsyncContext().complete();
      } else {
        response.closeOutput();
      }
    } catch (IOException e) {
      log.debug("exception found while closing resources {} {} {}", method(), pathString(), e);
    }
    this.router = null;
    this.request = null;
    this.response = null;
  }

  private void ifStartAsync() {
    if (!request.isAsyncStarted()) {
      request.startAsync();
    }
  }

  private void ifSetChunked() {
    if (response.getHeader(HttpHeader.CONTENT_LENGTH.name()) == null) {
      response.setHeader(HttpHeader.TRANSFER_ENCODING, HttpHeaderValue.CHUNKED.asString());
    }
  }

  private FileUpload register(FileUpload upload) {
    if (files == null) {
      files = new ArrayList<>();
    }
    files.add(upload);
    return upload;
  }

  private static void formParam(Request request, Formdata form) {
    Enumeration<String> names = request.getParameterNames();
    MultiMap<String> query = request.getQueryParameters();
    while (names.hasMoreElements()) {
      String name = names.nextElement();
      if (query == null || !query.containsKey(name)) {
        form.put(name, request.getParameter(name));
      }
    }
  }

}