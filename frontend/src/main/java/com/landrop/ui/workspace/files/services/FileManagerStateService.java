package com.landrop.ui.workspace.files.services;

import com.landrop.ui.cloud.model.Domain;
import com.landrop.ui.workspace.files.api.FileManagerApi;
import com.landrop.ui.workspace.services.WorkspaceStateService;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Workspace-local file/folder state. Binary data remains exclusively in the LAN transfer engine. */
public final class FileManagerStateService implements AutoCloseable {
    private final WorkspaceStateService workspaces; private final FileManagerApi api;
    private final ObservableList<Domain.Folder> folders=FXCollections.observableArrayList();
    private final ObservableList<Domain.File> files=FXCollections.observableArrayList();
    private final ObservableList<Domain.Folder> breadcrumbs=FXCollections.observableArrayList();
    private final ObjectProperty<Domain.Folder> currentFolder=new SimpleObjectProperty<>();
    private final BooleanProperty loading=new SimpleBooleanProperty(), busy=new SimpleBooleanProperty();
    private final StringProperty error=new SimpleStringProperty(""), success=new SimpleStringProperty("");
    private final AtomicLong generation=new AtomicLong(); private boolean closed;
    public FileManagerStateService(WorkspaceStateService workspaces, FileManagerApi api){this.workspaces=Objects.requireNonNull(workspaces);this.api=Objects.requireNonNull(api);workspaces.activeWorkspaceProperty().addListener((o,a,b)->reset(b)); if(workspaces.getActiveWorkspace()!=null)reset(workspaces.getActiveWorkspace());}
    public ObservableList<Domain.Folder> folders(){return folders;} public ObservableList<Domain.File> files(){return files;} public ObservableList<Domain.Folder> breadcrumbs(){return breadcrumbs;}
    public ReadOnlyObjectProperty<Domain.Folder> currentFolderProperty(){return currentFolder;} public Domain.Folder currentFolder(){return currentFolder.get();} public ReadOnlyBooleanProperty loadingProperty(){return loading;} public ReadOnlyBooleanProperty busyProperty(){return busy;} public ReadOnlyStringProperty errorProperty(){return error;} public ReadOnlyStringProperty successProperty(){return success;}
    private void reset(Domain.Workspace ws){long token=generation.incrementAndGet();run(()->{folders.clear();files.clear();breadcrumbs.clear();currentFolder.set(null);error.set("");success.set("");});if(ws!=null)load(null,token);}
    public void openRoot(){Domain.Workspace ws=workspaces.getActiveWorkspace();if(ws!=null){long t=generation.incrementAndGet();run(()->{currentFolder.set(null);breadcrumbs.clear();});load(null,t);}}
    public void openFolder(Domain.Folder folder){if(folder==null)return;long t=generation.incrementAndGet();run(()->{currentFolder.set(folder); rebuildBreadcrumbs(folder);});load(folder.id(),t);}
    private record Listing(List<Domain.Folder> folders, List<Domain.File> files) { }
    private void load(String parent,long token){Domain.Workspace ws=workspaces.getActiveWorkspace();if(ws==null)return;run(()->{loading.set(true);error.set("");});api.listFolders(ws.id(),parent).thenCombine(api.listFiles(ws.id(),parent),Listing::new).whenComplete((result,fail)->run(()->{if(closed||token!=generation.get())return;loading.set(false);if(fail!=null){error.set(problem(fail));return;}folders.setAll(result.folders());files.setAll(result.files());}));}
    private void rebuildBreadcrumbs(Domain.Folder folder){List<Domain.Folder> path=new ArrayList<>();path.add(folder);String parent=folder.parentId();while(parent!=null){Domain.Folder next=null;for(Domain.Folder f:breadcrumbs)if(parent.equals(f.id()))next=f;if(next==null)break;path.add(next);parent=next.parentId();}Collections.reverse(path);breadcrumbs.setAll(path);}
    public CompletableFuture<Domain.Folder> createFolder(String name){Domain.Workspace ws=workspaces.getActiveWorkspace();if(ws==null)return CompletableFuture.failedFuture(new IllegalStateException("Select a workspace first."));if(name==null||name.trim().isEmpty())return CompletableFuture.failedFuture(new IllegalArgumentException("Folder name is required."));return mutate(api.createFolder(ws.id(),currentFolder.get()==null?null:currentFolder.get().id(),name.trim()),"Folder created.");}
    public CompletableFuture<Domain.Folder> renameFolder(Domain.Folder folder,String name){return mutate(api.updateFolder(folder.id(),name.trim(),folder.parentId()),"Folder renamed.");}
    public CompletableFuture<Domain.Folder> moveFolder(Domain.Folder folder,String parentId){return mutate(api.updateFolder(folder.id(),folder.name(),parentId),"Folder moved.");}
    public CompletableFuture<Domain.File> renameFile(Domain.File file,String name){return mutate(api.updateFile(file.id(),name.trim(),file.folderId()),"File renamed.");}
    public CompletableFuture<Domain.File> moveFile(Domain.File file,String folderId){return mutate(api.updateFile(file.id(),file.name(),folderId),"File moved.");}
    public CompletableFuture<Void> deleteFolder(Domain.Folder folder){return mutateVoid(api.deleteFolder(folder.id()),"Folder deleted.");} public CompletableFuture<Void> deleteFile(Domain.File file){return mutateVoid(api.deleteFile(file.id()),"File deleted.");}
    public CompletableFuture<Domain.File> registerAndRequest(Path local,String userId,String deviceId){Domain.Workspace ws=workspaces.getActiveWorkspace();if(ws==null)return CompletableFuture.failedFuture(new IllegalStateException("Select a workspace first."));run(()->{busy.set(true);error.set("");});return api.registerFile(ws.id(),currentFolder.get()==null?null:currentFolder.get().id(),local).thenCompose(file->api.requestTransfer(file.id(),userId,deviceId).thenApply(x->file)).whenComplete((f,fail)->run(()->{busy.set(false);if(fail!=null)error.set(problem(fail));else{success.set("Transfer request sent. Waiting for acceptance.");refresh();}}));}
    public CompletableFuture<Void> requestExistingTransfer(Domain.File file,String userId,String deviceId){
        run(()->{busy.set(true);error.set("");});
        return api.requestTransfer(file.id(),userId,deviceId).whenComplete((x,fail)->run(()->{busy.set(false);if(fail!=null)error.set(problem(fail));else success.set("Transfer request sent. Waiting for acceptance.");}));
    }
    public CompletableFuture<List<Domain.Device>> devicesFor(String userId){Domain.Workspace ws=workspaces.getActiveWorkspace();return ws==null?CompletableFuture.completedFuture(List.of()):api.listDevices(ws.id(),userId);}
    public void refresh(){Domain.Folder folder=currentFolder.get();load(folder==null?null:folder.id(),generation.get());}
    private <T> CompletableFuture<T> mutate(CompletableFuture<T> future,String text){run(()->{busy.set(true);error.set("");});return future.whenComplete((x,fail)->run(()->{busy.set(false);if(fail!=null)error.set(problem(fail));else{success.set(text);refresh();}}));}
    private CompletableFuture<Void> mutateVoid(CompletableFuture<Void> future,String text){return mutate(future,text);}
    private static String problem(Throwable e){Throwable x=e;while((x instanceof java.util.concurrent.CompletionException||x instanceof java.util.concurrent.ExecutionException)&&x.getCause()!=null)x=x.getCause();return x.getMessage()==null?"File request failed.":x.getMessage();}
    @Override public void close(){closed=true;generation.incrementAndGet();run(()->{folders.clear();files.clear();breadcrumbs.clear();currentFolder.set(null);});}
    private static void run(Runnable r){try{if(Platform.isFxApplicationThread())r.run();else Platform.runLater(r);}catch(IllegalStateException e){r.run();}}
}
