package com.landrop.ui.controllers;

import com.landrop.ui.lan.LanDiscoveryService;
import com.landrop.ui.lan.UIStateLanServices;
import com.landrop.ui.lan.model.DiscoveredDevice;
import com.landrop.ui.services.UIStateService;
import com.landrop.ui.utils.AlertUtils;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/** LAN endpoint browser. Product users are resolved by the server/device registry elsewhere. */
public class PeersController {
    @FXML private Label peersSubtitleLabel; @FXML private Button refreshButton; @FXML private VBox peersContainer, emptyStateContainer;
    private UIStateService stateService; private LanDiscoveryService discovery;
    public void init(UIStateService stateService, MainController mainController) {
        this.stateService=stateService;
        this.discovery=mainController!=null&&mainController.getMainApp()!=null&&mainController.getMainApp().getLanDiscoveryService()!=null
                ? mainController.getMainApp().getLanDiscoveryService() : new UIStateLanServices(stateService);
        discovery.onlineDeviceCountProperty().addListener((o,a,b)->Platform.runLater(this::renderCount));
        discovery.discoveredDevices().addListener((ListChangeListener<DiscoveredDevice>)c->Platform.runLater(this::renderDevices));
        stateService.p2pStatusProperty().addListener((o,a,b)->Platform.runLater(this::renderDevices)); renderCount();renderDevices();
    }
    private void renderCount(){int n=discovery.onlineDeviceCountProperty().get();peersSubtitleLabel.setText(n+(n==1?" device online":" devices online"));}
    private void renderDevices(){peersContainer.getChildren().clear();boolean any=!discovery.discoveredDevices().isEmpty();emptyStateContainer.setVisible(!any);emptyStateContainer.setManaged(!any);peersContainer.setVisible(any);peersContainer.setManaged(any);for(DiscoveredDevice device:discovery.discoveredDevices())peersContainer.getChildren().add(card(device));}
    private HBox card(DiscoveredDevice device){HBox card=new HBox(16);card.getStyleClass().add("peer-card");card.setAlignment(Pos.CENTER_LEFT);VBox info=new VBox(3);Label name=new Label(device.deviceName());name.getStyleClass().add("peer-card-name");Label endpoint=new Label("LAN device • "+device.diagnosticEndpoint());endpoint.getStyleClass().add("peer-card-address");info.getChildren().addAll(name,endpoint);Region gap=new Region();HBox.setHgrow(gap,Priority.ALWAYS);Label availability=new Label("LAN AVAILABLE");availability.getStyleClass().add("status-badge-online");Button connect=new Button(stateService.isP2pConnected()?"Connected":"Connect");connect.getStyleClass().add("primary-button");connect.setDisable(stateService.isP2pConnected());connect.setOnAction(e->{connect.setDisable(true);connect.setText("Connecting…");discovery.connect(device).thenRun(()->Platform.runLater(()->connect.setText("Connected"))).exceptionally(error->{Platform.runLater(()->{connect.setDisable(false);connect.setText("Connect");AlertUtils.showError("LAN Device Connection Failed","Unable to connect to "+device.deviceName(),message(error));});return null;});});card.getChildren().addAll(new Circle(5,Color.web("#10b981")),info,gap,availability,connect);return card;}
    @FXML private void onRefreshClicked(){refreshButton.setDisable(true);refreshButton.setText("Refreshing…");discovery.refresh().whenComplete((ignored,error)->Platform.runLater(()->{refreshButton.setDisable(false);refreshButton.setText("⟳ Refresh");if(error!=null)AlertUtils.showError("Discovery Failed","Unable to fetch LAN devices",message(error));}));}
    private static String message(Throwable error){return error.getCause()!=null&&error.getCause().getMessage()!=null?error.getCause().getMessage():error.getMessage();}
}
