package com.landrop.ui.lan;

import com.landrop.transfer.FileTransfer;
import javafx.collections.ObservableList;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** UI-safe direct binary transfer boundary. Coordination/authorization remains server-owned. */
public interface LanTransferService {
    ObservableList<FileTransfer> transfers();
    CompletableFuture<FileTransfer> offerLocalFile(File file);
    CompletableFuture<Void> accept(String transferId);
    CompletableFuture<Void> reject(String transferId, String reason);
    CompletableFuture<Void> cancel(String transferId);
    default CompletableFuture<Void> pause(String transferId) { return CompletableFuture.completedFuture(null); }
    default CompletableFuture<Void> resume(String transferId) { return CompletableFuture.completedFuture(null); }
    void setOnTransferUpdated(Consumer<FileTransfer> listener);
}
