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

package org.eclipse.jetty.server.handler.gzip;

import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class GzipHttpOutputInterceptor implements HttpOutput.Interceptor
{
    public static Logger LOG = Log.getLogger(GzipHttpOutputInterceptor.class);
    private final static PreEncodedHttpField CONTENT_ENCODING_GZIP=new PreEncodedHttpField(HttpHeader.CONTENT_ENCODING,"gzip");
    private final static byte[] GZIP_HEADER = new byte[] { (byte)0x1f, (byte)0x8b, Deflater.DEFLATED, 0, 0, 0, 0, 0, 0, 0 };

    public final static HttpField VARY_ACCEPT_ENCODING_USER_AGENT=new PreEncodedHttpField(HttpHeader.VARY,HttpHeader.ACCEPT_ENCODING+", "+HttpHeader.USER_AGENT);
    public final static HttpField VARY_ACCEPT_ENCODING=new PreEncodedHttpField(HttpHeader.VARY,HttpHeader.ACCEPT_ENCODING.asString());
    
    private enum GZState {  MIGHT_COMPRESS, NOT_COMPRESSING, COMMITTING, COMPRESSING, FINISHED};
    private final AtomicReference<GZState> _state = new AtomicReference<>(GZState.MIGHT_COMPRESS);
    private final CRC32 _crc = new CRC32();

    private final GzipFactory _factory;
    private final HttpOutput.Interceptor _interceptor;
    private final HttpChannel _channel;
    private final HttpField _vary;
    private final int _bufferSize;
    
    private Deflater _deflater;
    private ByteBuffer _buffer;

    public GzipHttpOutputInterceptor(GzipFactory factory, HttpChannel channel, HttpOutput.Interceptor next)
    {
        this(factory,VARY_ACCEPT_ENCODING_USER_AGENT,channel.getHttpConfiguration().getOutputBufferSize(),channel,next);
    }
    
    public GzipHttpOutputInterceptor(GzipFactory factory, HttpField vary, HttpChannel channel, HttpOutput.Interceptor next)
    {
        this(factory,vary,channel.getHttpConfiguration().getOutputBufferSize(),channel,next);
    }
    
    public GzipHttpOutputInterceptor(GzipFactory factory, HttpField vary, int bufferSize, HttpChannel channel, HttpOutput.Interceptor next)
    {
        _factory=factory;
        _channel=channel;
        _interceptor=next;
        _vary=vary;
        _bufferSize=bufferSize;
    }

    public HttpOutput.Interceptor getNextInterceptor()
    {
        return _interceptor;
    }
    
    @Override
    public void write(ByteBuffer content, boolean complete, Callback callback)
    {
        switch (_state.get())
        {
            case MIGHT_COMPRESS:
                commit(content,complete,callback);
                break;
                
            case NOT_COMPRESSING:
                _interceptor.write(content, complete, callback);
                return;
                
            case COMMITTING:
                callback.failed(new WritePendingException());
                break;

            case COMPRESSING:
                gzip(content,complete,callback);
                break;

            default:
                callback.failed(new IllegalStateException("state="+_state.get()));
                break;
        }
    }

    private void addTrailer()
    {
        int i=_buffer.limit();
        _buffer.limit(i+8);
        
        int v=(int)_crc.getValue();
        _buffer.put(i++,(byte)(v & 0xFF));
        _buffer.put(i++,(byte)((v>>>8) & 0xFF));
        _buffer.put(i++,(byte)((v>>>16) & 0xFF));
        _buffer.put(i++,(byte)((v>>>24) & 0xFF));
        
        v=_deflater.getTotalIn();
        _buffer.put(i++,(byte)(v & 0xFF));
        _buffer.put(i++,(byte)((v>>>8) & 0xFF));
        _buffer.put(i++,(byte)((v>>>16) & 0xFF));
        _buffer.put(i++,(byte)((v>>>24) & 0xFF));
    }
    
    
    private void gzip(ByteBuffer content, boolean complete, final Callback callback)
    {
        if (content.hasRemaining() || complete)
        {
            if (content.hasArray())
                new GzipArrayCB(content,complete,callback).iterate();
            else
                new GzipBufferCB(content,complete,callback).iterate();
        }
        else
            callback.succeeded();
    }

    protected void commit(ByteBuffer content, boolean complete, Callback callback)
    {
        // Are we excluding because of status?
        int sc = _channel.getResponse().getStatus();
        if (sc>0 && (sc<200 || sc==204 || sc==205 || sc>=300))
        {
            LOG.debug("{} exclude by status {}",this,sc);
            noCompression();
            _interceptor.write(content, complete, callback);
            return;
        }
        
        // Are we excluding because of mime-type?
        String ct = _channel.getResponse().getContentType();
        if (ct!=null)
        {
            ct=MimeTypes.getContentTypeWithoutCharset(ct);
            if (!_factory.isMimeTypeGzipable(StringUtil.asciiToLowerCase(ct)))
            {
                LOG.debug("{} exclude by mimeType {}",this,ct);
                noCompression();
                _interceptor.write(content, complete, callback);
                return;
            }
        }
        
        // Has the Content-Encoding header already been set?
        String ce=_channel.getResponse().getHeader("Content-Encoding");
        if (ce != null)
        {
            LOG.debug("{} exclude by content-encoding {}",this,ce);
            noCompression();
            _interceptor.write(content, complete, callback);
            return;
        }
        
        // Are we the thread that commits?
        if (_state.compareAndSet(GZState.MIGHT_COMPRESS,GZState.COMMITTING))
        {
            // We are varying the response due to accept encoding header.
            HttpFields fields = _channel.getResponse().getHttpFields();
            fields.add(_vary);

            long content_length = _channel.getResponse().getContentLength();
            if (content_length<0 && complete)
                content_length=content.remaining();
            
            _deflater = _factory.getDeflater(_channel.getRequest(),content_length);
            
            if (_deflater==null)
            {
                LOG.debug("{} exclude no deflater",this);
                _state.set(GZState.NOT_COMPRESSING);
                _interceptor.write(content, complete, callback);
                return;
            }

            fields.put(CONTENT_ENCODING_GZIP);
            _crc.reset();
            _buffer=_channel.getByteBufferPool().acquire(_bufferSize,false);
            BufferUtil.fill(_buffer,GZIP_HEADER,0,GZIP_HEADER.length);

            // Adjust headers
            _channel.getResponse().setContentLength(-1);
            String etag=fields.get(HttpHeader.ETAG);
            if (etag!=null)
            {
                int end = etag.length()-1;
                etag=(etag.charAt(end)=='"')?etag.substring(0,end)+GzipHandler.ETAG_GZIP+'"':etag+GzipHandler.ETAG_GZIP;
                fields.put(HttpHeader.ETAG,etag);
            }
            
            
            LOG.debug("{} compressing {}",this,_deflater);
            _state.set(GZState.COMPRESSING);
            
            gzip(content,complete,callback);
        }
        else
            callback.failed(new WritePendingException());
    }

    public void noCompression()
    {
        while (true)
        {
            switch (_state.get())
            {
                case NOT_COMPRESSING:
                    return;

                case MIGHT_COMPRESS:
                    if (_state.compareAndSet(GZState.MIGHT_COMPRESS,GZState.NOT_COMPRESSING))
                        return;
                    break;

                default:
                    throw new IllegalStateException(_state.get().toString());
            }
        }
    }

    public void noCompressionIfPossible()
    {
        while (true)
        {
            switch (_state.get())
            {
                case COMPRESSING:
                case NOT_COMPRESSING:
                    return;

                case MIGHT_COMPRESS:
                    if (_state.compareAndSet(GZState.MIGHT_COMPRESS,GZState.NOT_COMPRESSING))
                        return;
                    break;

                default:
                    throw new IllegalStateException(_state.get().toString());
            }
        }
    }
    
    public boolean mightCompress()
    {
        return _state.get()==GZState.MIGHT_COMPRESS;
    }
    private class GzipArrayCB extends IteratingNestedCallback
    {        
        private final boolean _complete;
        public GzipArrayCB(ByteBuffer content, boolean complete, Callback callback)
        {
            super(callback);
            _complete=complete;

             byte[] array=content.array();
             int off=content.arrayOffset()+content.position();
             int len=content.remaining();
             _crc.update(array,off,len);
             _deflater.setInput(array,off,len);
             if (complete)
                 _deflater.finish();
             content.position(content.limit());
        }

        @Override
        protected Action process() throws Exception
        {
            if (_deflater.needsInput())
            {
                if (_deflater.finished())
                {
                    _factory.recycle(_deflater);
                    _deflater=null;
                    _channel.getByteBufferPool().release(_buffer);
                    _buffer=null;
                    return Action.SUCCEEDED;
                }

                if (!_complete)
                    return Action.SUCCEEDED;
                
                _deflater.finish();
            }

            BufferUtil.compact(_buffer);
            int off=_buffer.arrayOffset()+_buffer.limit();
            int len=_buffer.capacity()-_buffer.limit()- (_complete?8:0);
            int produced=_deflater.deflate(_buffer.array(),off,len,Deflater.NO_FLUSH);
            _buffer.limit(_buffer.limit()+produced);
            boolean complete=_deflater.finished();
            if (complete)
                addTrailer();
            
            _interceptor.write(_buffer,complete,this);
            return Action.SCHEDULED;
        }
    }
    
    private class GzipBufferCB extends IteratingNestedCallback
    {        
        private final ByteBuffer _input;
        private final ByteBuffer _content;
        private final boolean _last;
        public GzipBufferCB(ByteBuffer content, boolean complete, Callback callback)
        {
            super(callback);
            _input=_channel.getByteBufferPool().acquire(Math.min(_bufferSize,content.remaining()),false);
            _content=content;
            _last=complete;
        }

        @Override
        protected Action process() throws Exception
        {
            if (_deflater.needsInput())
            {                
                if (BufferUtil.isEmpty(_content))
                {                    
                    if (_deflater.finished())
                    {
                        _factory.recycle(_deflater);
                        _deflater=null;
                        _channel.getByteBufferPool().release(_buffer);
                        _buffer=null;
                        return Action.SUCCEEDED;
                    }
                    
                    if (!_last)
                    {
                        return Action.SUCCEEDED;
                    }
                    
                    _deflater.finish();
                }
                else
                {
                    BufferUtil.clearToFill(_input);
                    int took=BufferUtil.put(_content,_input);
                    BufferUtil.flipToFlush(_input,0);
                    if (took==0)
                        throw new IllegalStateException();
                   
                    byte[] array=_input.array();
                    int off=_input.arrayOffset()+_input.position();
                    int len=_input.remaining();

                    _crc.update(array,off,len);
                    _deflater.setInput(array,off,len);                
                    if (_last && BufferUtil.isEmpty(_content))
                        _deflater.finish();
                }
            }

            BufferUtil.compact(_buffer);
            int off=_buffer.arrayOffset()+_buffer.limit();
            int len=_buffer.capacity()-_buffer.limit() - (_last?8:0);
            int produced=_deflater.deflate(_buffer.array(),off,len,Deflater.NO_FLUSH);
            
            _buffer.limit(_buffer.limit()+produced);
            boolean finished=_deflater.finished();
            
            if (finished)
                addTrailer();
                
            _interceptor.write(_buffer,finished,this);
            return Action.SCHEDULED;
        }
    }
}