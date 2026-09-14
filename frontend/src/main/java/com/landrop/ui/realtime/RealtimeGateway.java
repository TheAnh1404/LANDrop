package com.landrop.ui.realtime;

import com.landrop.ui.auth.services.AuthStateService;
import com.landrop.ui.models.RealtimeConnectionStatus;
import com.landrop.ui.services.RealtimeTransport;
import com.landrop.ui.services.WebSocketRealtimeTransport;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The sole realtime connection owner. Current backend authenticates sockets per channel,
 * therefore changing a channel replaces the physical socket but never leaves parallel listeners.
 */
public final class RealtimeGateway implements AutoCloseable {
    public interface SubscriptionListener {
        void onMessage(String payload);
        default void onError(Throwable error) { }
    }
    private final URI baseUri; private final AuthStateService auth;
    private final ObjectProperty<RealtimeConnectionStatus> status = new SimpleObjectProperty<>(RealtimeConnectionStatus.DISCONNECTED);
    private final AtomicLong version = new AtomicLong();
    private volatile RealtimeTransport transport; private volatile SubscriptionListener listener; private volatile boolean closed;
    public RealtimeGateway(URI baseUri, AuthStateService auth) { this.baseUri=Objects.requireNonNull(baseUri); this.auth=Objects.requireNonNull(auth); }
    public ObjectProperty<RealtimeConnectionStatus> statusProperty() { return status; }
    public synchronized AutoCloseable subscribeChannel(String channelId, SubscriptionListener next) {
        Objects.requireNonNull(channelId); Objects.requireNonNull(next); long token=version.incrementAndGet(); closeTransport(); listener=next; closed=false;
        String tokenValue=auth.accessToken().orElse(null); if(tokenValue==null||tokenValue.isBlank()){ update(RealtimeConnectionStatus.DISCONNECTED); next.onError(new IllegalStateException("Sign in is required.")); return () -> {}; }
        WebSocketRealtimeTransport socket=new WebSocketRealtimeTransport(channelUri(channelId),tokenValue); transport=socket; update(RealtimeConnectionStatus.CONNECTING);
        socket.setListener(new RealtimeTransport.Listener() {
            @Override public void onOpen(){if(token==version.get())update(RealtimeConnectionStatus.CONNECTED);}
            @Override public void onMessage(String payload){if(token==version.get()&&listener!=null)listener.onMessage(payload);}
            @Override public void onClose(int code,String reason){if(token==version.get()&&!closed){update(RealtimeConnectionStatus.RECONNECTING);retry(channelId,token);}}
            @Override public void onError(Throwable error){if(token==version.get()&&!closed){if(listener!=null)listener.onError(error);update(RealtimeConnectionStatus.RECONNECTING);retry(channelId,token);}}
        });
        socket.connect().exceptionally(error->{if(token==version.get()){update(RealtimeConnectionStatus.DISCONNECTED);next.onError(error);}return null;});
        return () -> { if(version.compareAndSet(token,token+1)) { closed=true; closeTransport(); update(RealtimeConnectionStatus.DISCONNECTED); } };
    }
    private void retry(String channel,long token){CompletableFuture.delayedExecutor(3,TimeUnit.SECONDS).execute(()->{if(!closed&&token==version.get()&&listener!=null)subscribeChannel(channel,listener);});}
    private URI channelUri(String channel){return URI.create(baseUri.getScheme()+"://"+baseUri.getAuthority()+"/api/realtime/channels/"+channel);}
    private void closeTransport(){RealtimeTransport current=transport;transport=null;if(current!=null)current.close();}
    private void update(RealtimeConnectionStatus next){try{if(Platform.isFxApplicationThread())status.set(next);else Platform.runLater(()->status.set(next));}catch(IllegalStateException e){status.set(next);}}
    @Override public synchronized void close(){closed=true;version.incrementAndGet();listener=null;closeTransport();update(RealtimeConnectionStatus.DISCONNECTED);}
}
