package com.landrop.ui.controllers;

import com.landrop.transfer.FileTransfer;
import com.landrop.transfer.FileTransferDirection;
import com.landrop.transfer.FileTransferStatus;
import com.landrop.ui.cloud.model.Domain;
import com.landrop.ui.services.UIStateService;
import com.landrop.ui.lan.LanTransferService;
import com.landrop.ui.workspace.files.services.FileManagerStateService;
import com.landrop.ui.workspace.services.WorkspaceStateService;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.Node;
import javafx.stage.FileChooser;
import javafx.scene.layout.*;

import java.io.File;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Unified workspace Files surface. Existing LAN FileTransfer objects remain the progress/acceptance engine. */
public final class TransfersController {
    @FXML private Label workspaceNameLabel,breadcrumbLabel,errorLabel,successLabel,detailsLabel;
    @FXML private VBox foldersContainer,filesContainer,transfersContainer,emptyFilesContainer;
    @FXML private Button rootButton,newFolderButton,selectFileButton;
    private LanTransferService lanTransfer; private WorkspaceStateService workspaces; private FileManagerStateService files;
    private long lastRenderMs;

    public void init(UIStateService state, MainController main) {
        this.lanTransfer=main.getMainApp()!=null?main.getMainApp().getLanTransferService():new com.landrop.ui.lan.UIStateLanServices(state); this.workspaces=main.getWorkspaceStateService();
        if(main.getMainApp()!=null) this.files=main.getMainApp().getFileManagerStateService();
        if(files==null||workspaces==null){workspaceNameLabel.setText("Files unavailable until you sign in to a workspace.");return;}
        files.folders().addListener((ListChangeListener<Domain.Folder>)c->Platform.runLater(this::renderFiles)); files.files().addListener((ListChangeListener<Domain.File>)c->Platform.runLater(this::renderFiles)); files.breadcrumbs().addListener((ListChangeListener<Domain.Folder>)c->Platform.runLater(this::renderBreadcrumb));
        files.errorProperty().addListener((o,a,b)->Platform.runLater(()->errorLabel.setText(b))); files.successProperty().addListener((o,a,b)->Platform.runLater(()->successLabel.setText(b))); workspaces.activeWorkspaceProperty().addListener((o,a,b)->Platform.runLater(()->{workspaceNameLabel.setText(b==null?"No workspace selected":b.name());detailsLabel.setText("");renderFiles();}));
        lanTransfer.transfers().addListener((ListChangeListener<FileTransfer>)c->scheduleTransferRender()); lanTransfer.setOnTransferUpdated(t->scheduleTransferRender());
        workspaceNameLabel.setText(workspaces.getActiveWorkspace()==null?"No workspace selected":workspaces.getActiveWorkspace().name()); renderBreadcrumb();renderFiles();renderTransfers();
    }
    private void scheduleTransferRender(){long now=System.currentTimeMillis();if(now-lastRenderMs<100)return;lastRenderMs=now;Platform.runLater(this::renderTransfers);}
    private void renderBreadcrumb(){StringBuilder text=new StringBuilder("Files");for(Domain.Folder f:files.breadcrumbs())text.append("  >  ").append(f.name());breadcrumbLabel.setText(text.toString());}
    private void renderFiles(){foldersContainer.getChildren().clear();filesContainer.getChildren().clear();boolean empty=files.folders().isEmpty()&&files.files().isEmpty();emptyFilesContainer.setVisible(empty);emptyFilesContainer.setManaged(empty);
        for(Domain.Folder folder:files.folders())foldersContainer.getChildren().add(folderRow(folder)); for(Domain.File file:files.files())filesContainer.getChildren().add(fileRow(file));}
    private HBox folderRow(Domain.Folder folder){HBox row=new HBox(10);row.setAlignment(Pos.CENTER_LEFT);row.getStyleClass().add("file-row");Button open=new Button("📁  "+folder.name());open.getStyleClass().add("file-name-button");open.setOnAction(e->files.openFolder(folder));Region gap=new Region();HBox.setHgrow(gap,Priority.ALWAYS);row.getChildren().addAll(open,gap,actionsForFolder(folder));return row;}
    private HBox fileRow(Domain.File file){HBox row=new HBox(10);row.setAlignment(Pos.CENTER_LEFT);row.getStyleClass().add("file-row");Button info=new Button(icon(file.mimeType())+"  "+file.name());info.getStyleClass().add("file-name-button");info.setOnAction(e->showDetails(file));VBox meta=new VBox(1,new Label(formatSize(file.sizeBytes())+" • "+uploader(file.uploadedById())),new Label(DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneId.systemDefault()).format(file.createdAt())));meta.getStyleClass().add("file-meta");Region gap=new Region();HBox.setHgrow(gap,Priority.ALWAYS);Button send=small("Send",e->chooseLocalAndReceiver(file));row.getChildren().addAll(info,meta,gap,send,actionsForFile(file));return row;}
    private HBox actionsForFolder(Domain.Folder f){HBox box=new HBox(4);box.getChildren().addAll(small("Rename",e->renameFolder(f)),small("Move",e->moveFolder(f)),small("Delete",e->deleteFolder(f)));return box;}
    private HBox actionsForFile(Domain.File f){HBox box=new HBox(4);box.getChildren().addAll(small("Rename",e->renameFile(f)),small("Move",e->moveFile(f)),small("Delete",e->deleteFile(f)));return box;}
    private Button small(String text,javafx.event.EventHandler<javafx.event.ActionEvent> h){Button b=new Button(text);b.getStyleClass().add("message-action-button");b.setOnAction(h);return b;}
    private void showDetails(Domain.File f){detailsLabel.setText("Name: "+f.name()+"\nSize: "+formatSize(f.sizeBytes())+"\nType: "+f.mimeType()+"\nUploaded by: "+uploader(f.uploadedById())+"\nCreated: "+f.createdAt()+"\nHash: "+(f.sha256()==null?"Not available":f.sha256()));}
    private void renameFolder(Domain.Folder f){prompt("Rename folder",f.name(),name->files.renameFolder(f,name));} private void renameFile(Domain.File f){prompt("Rename file",f.name(),name->files.renameFile(f,name));}
    private void prompt(String title,String initial,java.util.function.Function<String,java.util.concurrent.CompletableFuture<?>> action){TextInputDialog d=new TextInputDialog(initial);d.setTitle(title);d.setHeaderText(null);d.setContentText("Name:");d.showAndWait().ifPresent(name->{if(name==null||name.trim().isEmpty()){errorLabel.setText("Name is required.");return;}action.apply(name);});}
    private void moveFolder(Domain.Folder f){chooseFolder("Move folder",target->files.moveFolder(f,target));} private void moveFile(Domain.File f){chooseFolder("Move file",target->files.moveFile(f,target));}
    private void chooseFolder(String title,java.util.function.Consumer<String> action){ChoiceDialog<String> d=new ChoiceDialog<>("Workspace root",java.util.stream.Stream.concat(java.util.stream.Stream.of("Workspace root"),files.folders().stream().map(Domain.Folder::name)).toList());d.setTitle(title);d.setHeaderText(null);d.setContentText("Destination:");d.showAndWait().ifPresent(choice->{String id="Workspace root".equals(choice)?null:files.folders().stream().filter(x->x.name().equals(choice)).map(Domain.Folder::id).findFirst().orElse(null);action.accept(id);});}
    private void deleteFolder(Domain.Folder f){confirm("Delete folder ‘"+f.name()+"’?",()->files.deleteFolder(f));} private void deleteFile(Domain.File f){confirm("Delete file ‘"+f.name()+"’?",()->files.deleteFile(f));}
    private void confirm(String text,Runnable yes){Alert d=new Alert(Alert.AlertType.CONFIRMATION,text,ButtonType.CANCEL,ButtonType.OK);d.setHeaderText("Confirm deletion");if(d.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK)yes.run();}
    @FXML private void onRoot(){files.openRoot();} @FXML private void onNewFolder(){TextInputDialog d=new TextInputDialog();d.setTitle("New Folder");d.setHeaderText("Create folder");d.setContentText("Name:");d.showAndWait().ifPresent(name->{if(name.trim().isEmpty())errorLabel.setText("Folder name is required.");else files.createFolder(name);});}
    @FXML private void onSelectFile(){chooseLocalAndReceiver(null);}
    private void chooseLocalAndReceiver(Domain.File existing){if(existing!=null){receiverDialog(null,existing);return;}FileChooser chooser=new FileChooser();chooser.setTitle("Select file to register and send");File selected=chooser.showOpenDialog(selectFileButton.getScene().getWindow());if(selected==null)return;receiverDialog(selected.toPath(),null);}
    private void receiverDialog(Path local,Domain.File existing){Dialog<ButtonType> dialog=new Dialog<>();dialog.setTitle("Send file");dialog.setHeaderText("Choose a workspace user and LAN device");ButtonType sendType=new ButtonType("Send",ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL,sendType);ComboBox<Domain.WorkspaceMember> users=new ComboBox<>();users.getItems().setAll(workspaces.getActiveMembers().stream().filter(m->!m.userId().equals(workspaces.currentUserId())).toList());users.setCellFactory(x->memberCell());users.setButtonCell(memberCell());ComboBox<Domain.Device> devices=new ComboBox<>();devices.setCellFactory(x->deviceCell());devices.setButtonCell(deviceCell());Label availability=new Label("Choose a recipient.");VBox content=new VBox(10,new Label("Recipient"),users,new Label("Device"),devices,availability);dialog.getDialogPane().setContent(content);Node send=dialog.getDialogPane().lookupButton(sendType);send.setDisable(true);users.valueProperty().addListener((o,a,user)->{devices.getItems().clear();send.setDisable(true);if(user==null)return;availability.setText("Loading devices…");files.devicesFor(user.userId()).whenComplete((found,fail)->Platform.runLater(()->{if(fail!=null){availability.setText("Could not load devices: "+fail.getMessage());return;}devices.getItems().setAll(found);availability.setText(found.isEmpty()?"No reachable LAN device.":"Choose a device.");}));});devices.valueProperty().addListener((o,a,device)->{boolean reachable=device!=null&&device.lanAvailable();send.setDisable(!reachable);availability.setText(device==null?"Choose a device.":device.availabilityLabel());});dialog.showAndWait().filter(x->x==sendType).ifPresent(x->{Domain.WorkspaceMember user=users.getValue();Domain.Device device=devices.getValue();if(user!=null&&device!=null&&device.lanAvailable()){if(existing==null)files.registerAndRequest(local,user.userId(),device.id());else files.requestExistingTransfer(existing,user.userId(),device.id());}});}
    private ListCell<Domain.WorkspaceMember> memberCell(){return new ListCell<>(){@Override protected void updateItem(Domain.WorkspaceMember m,boolean empty){super.updateItem(m,empty);setText(empty||m==null?null:m.nameOrFallback());}};} private ListCell<Domain.Device> deviceCell(){return new ListCell<>(){@Override protected void updateItem(Domain.Device d,boolean empty){super.updateItem(d,empty);setText(empty||d==null?null:d.label()+" — "+d.availabilityLabel());}};}
    private void renderTransfers(){transfersContainer.getChildren().clear();for(FileTransfer t:lanTransfer.transfers())transfersContainer.getChildren().add(transferRow(t));}
    private HBox transferRow(FileTransfer t){boolean outgoing=t.getDirection()==FileTransferDirection.OUTGOING;HBox row=new HBox(10);row.getStyleClass().add("transfer-history-row");VBox info=new VBox(2,new Label(t.getFileName()),new Label((outgoing?"To device: ":"From device: ")+t.getRemotePeerName()));ProgressBar bar=new ProgressBar(t.getStatus()==FileTransferStatus.TRANSFERRING?t.getProgress():(t.getStatus()==FileTransferStatus.COMPLETED?1:0));bar.setPrefWidth(130);Label status=new Label(t.getStatus().getLabel()+(t.getStatus()==FileTransferStatus.TRANSFERRING?" "+t.getProgressPercent()+"%":""));Region gap=new Region();HBox.setHgrow(gap,Priority.ALWAYS);HBox actions=new HBox(4);if(t.getStatus()==FileTransferStatus.PENDING&&!outgoing){actions.getChildren().addAll(small("Accept",e->lanTransfer.accept(t.getTransferId())),small("Reject",e->lanTransfer.reject(t.getTransferId(),"Declined by user")));}else if(!t.getStatus().isTerminal()&&t.getStatus()!=FileTransferStatus.VERIFYING){actions.getChildren().add(small("Cancel",e->lanTransfer.cancel(t.getTransferId())));}row.getChildren().addAll(info,gap,bar,status,actions);return row;}
    private String uploader(String userId){if(userId.equals(workspaces.currentUserId())&&workspaces.authStateService().user()!=null)return workspaces.authStateService().user().displayName();for(Domain.WorkspaceMember m:workspaces.getActiveMembers())if(userId.equals(m.userId()))return m.nameOrFallback();return "User";} private static String icon(String mime){if(mime!=null&&mime.startsWith("image/"))return "🖼";if(mime!=null&&mime.contains("zip"))return "📦";return "📄";} private static String formatSize(BigInteger bytes){if(bytes.compareTo(BigInteger.valueOf(1024*1024))>=0)return String.format("%.1f MB",bytes.doubleValue()/(1024d*1024));if(bytes.compareTo(BigInteger.valueOf(1024))>=0)return String.format("%.1f KB",bytes.doubleValue()/1024d);return bytes+" B";}
}
