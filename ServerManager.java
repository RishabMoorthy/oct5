package org.framework.core;

import com.stubio.util.DataFile;
import com.stubio.util.DataSourceSelect;
import com.stubio.util.Endpoint;
import com.stubio.util.GenericProperty;
import com.stubio.util.StubOperation;
import com.stubio.util.VirtualServiceMapper;
import com.stubio.util.VirtualServiceObject;

import org.framework.config.LogConfigManager;
import org.framework.config.ServiceConfig;
import org.framework.constants.PathConstants;
import org.framework.datasource.AccessMode;
import org.framework.datasource.DataSourceDefinition;
import org.framework.datasource.DataSourceFactory;
import org.framework.datasource.DataSourceRegistry;
import org.framework.datasource.DataSourceService;
import org.framework.datasource.DataSourceType;
import org.framework.db.Utility;
import org.framework.db.service.ExecutionModeSyncException;
import org.framework.properties.Properties;
import org.framework.utils.CustomMethods;
import org.framework.utils.FolderDeleteService;
import org.framework.utils.Logger;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class ServerManager {

    private static ServerManager instance = new ServerManager();

    public Map<String, AbstractService> getServices() {
        return services;
    }

    private Map<String, AbstractService> services = new HashMap<>();

    private ServerManager(){
    }

    public static ServerManager getInstance() {
        return instance;
    }

    public AbstractService getService(String serviceName) {
        return services.get(serviceName);
    }

    /**
     * Parses a virtual service file with the com.stubio parser.
     *
     * <p>
     * The SoapUI class remapping below is retained for Groovy script bodies that
     * were carried over from the old format; it is inert for files that never
     * referenced com.eviware.
     */
    public VirtualServiceObject parseXml(File file) throws Exception {
        String content = new String (Files.readAllBytes(file.toPath()));
        //System.out.println(content);
        Files.write(file.toPath(),content.getBytes() );

        if (!content.contains("stubVirtualService")) {
            throw new UnsupportedOperationException(
                    "Not a stubVirtualService file. TCP (customMockService) and the old SoapUI formats "
                            + "are not supported by this parser - re-export the service in the new format.");
        }

        // DocumentBuilder.parse(String) resolves its argument as a URI, so a plain
        // path containing a space would fail here.
        return new VirtualServiceMapper(file.toURI().toString()).getVso();
    }

    public boolean deployService(VirtualServiceObject vso, boolean isDeployedFromUI, String user, String backendApplication, String group, String backendType, boolean storeToMasterCatalog, String envType) throws Exception {
        ServiceConfig config = new ServiceConfig();
        boolean isStoredInDb = false;
        try{
            config.setVirtualService(vso);
            config.setUserName(user);
            config.setXmlFileContent(readXmlContent(vso));
            config.setProperties(mapProperties(vso));
            config.setDataSourceService(new DataSourceService(buildDataSourceRegistry(vso), new DataSourceFactory()));

            AbstractService restService = AbstractServiceFactory.getInstance(config);
            config.setRequestHandler(RequestHandlerFactory.getInstance(config));
            restService.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm:ss a")));

            services.put(restService.getName(), restService);
            RespTimeConfigManager.getConfig().put(restService.getName(), "No");
            LogConfigManager.getConfig().put(restService.getName(), "No");

            //delete if any data source present when data source flag is false
            if(!config.isDatasourceEnabled()){
                FolderDeleteService.deleteServiceFolder(config.getServiceName());
            }

            boolean started = false;
            try{
                if (config.getAutoStart().equalsIgnoreCase("true")) {
                    restService.start();
                    started = true;
                }
                else{
                    restService.start();
                    restService.stop();
                    started = true;
                }
                boolean isStoredInMasterCatalog = false;
                if(storeToMasterCatalog)
                    isStoredInMasterCatalog = Utility.getInstance().storeServiceInMasterCatalog(restService.getName(), restService, backendApplication, group, backendType, envType);
                isStoredInDb =  Utility.getInstance().storeServiceInDataBase(restService.getName(), restService, isDeployedFromUI);
                if(!(isStoredInDb)){
                    try{
                        restService.stop();
                        services.remove(restService.getName());
                    }
                    catch(Exception e){
                        Logger.getInstance().error(config.getServiceName(), e);
                        e.printStackTrace();
                        return false;
                    }
                }
                return true;

            } catch(ExecutionModeSyncException e){
                // A misconfiguration the operator has to fix. Roll the service back
                // out and let the message reach the deploy response verbatim.
                Logger.getInstance().error(config.getServiceName(), e);
                if(started)
                    restService.stop();
                services.remove(restService.getName());
                throw e;
            } catch(Exception e){
                e.printStackTrace();
                Logger.getInstance().error(config.getServiceName(), e);
                if(started)
                    restService.stop();
                return false;
            }

            //store into db
        }
        catch(ExecutionModeSyncException e){
            throw e;
        }
        catch(Exception e){
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            Logger.getInstance().error("Error occurred in "+ config.getServiceName() + "\n" + sw.toString());
            return false;
        }
    }

    /** VirtualServiceObject.getXmlPath() holds the file URI we handed the mapper. */
    private String readXmlContent(VirtualServiceObject vso) throws Exception {
        String xmlPath = vso.getXmlPath();
        if (xmlPath == null || xmlPath.isBlank()) {
            return Files.readString(Paths.get(PathConstants.VS_XML_DIRECTORY, vso.getVsName() + PathConstants.VS_FILE_EXTENSION));
        }
        if (xmlPath.startsWith("file:")) {
            return Files.readString(Paths.get(java.net.URI.create(xmlPath)));
        }
        return Files.readString(Paths.get(xmlPath));
    }

    /** vs:Properties/vs:Variable pairs. */
    private Properties mapProperties(VirtualServiceObject vso) {
        Properties properties = new Properties();
        if (vso.getPropertyList() != null) {
            for (GenericProperty property : vso.getPropertyList()) {
                if (property.getKey() != null && !property.getKey().isBlank()) {
                    properties.addProperty(property.getKey(),
                            property.getValue() == null ? "" : property.getValue().trim());
                }
            }
        }
        return properties;
    }

    /**
     * Builds the datasource registry from every endpoint / operation
     * DataSourceSelect. Replaces the XPath scan in the old config.Parser.
     * Returns null when nothing usable is declared, matching the previous
     * behaviour that left the registry unset.
     */
    private DataSourceRegistry buildDataSourceRegistry(VirtualServiceObject vso) {
        DataSourceRegistry registry = new DataSourceRegistry();
        boolean registered = false;

        if (vso.getRestService() != null) {
            for (Endpoint endpoint : vso.getRestService().getEndpoints()) {
                registered |= registerFiles(endpoint.getDataSourceSelect(), registry, vso.getVsName());
            }
        }
        if (vso.getSoapService() != null) {
            for (StubOperation operation : vso.getSoapService().getStubOperations()) {
                registered |= registerFiles(operation.getDataSourceSelect(), registry, vso.getVsName());
            }
        }

        if (!registered) {
            Logger.getInstance().info("DataSource is not enabled for service " + vso.getVsName());
            return null;
        }
        return registry;
    }

    private boolean registerFiles(DataSourceSelect select, DataSourceRegistry registry, String serviceName) {
        if (select == null || select.getFile() == null) {
            return false;
        }

        boolean registered = false;
        for (DataFile file : select.getFile()) {
            DataSourceType dataSourceType = mapFileType(file.getFileType());
            if (dataSourceType == null) {
                // e.g. a <vs:File> carrying only <vs:RequestColumnMappings/>
                continue;
            }

            String name = firstNonBlank(file.getDsName(), file.getConnectionName());
            String fileLocation = file.getFileLocation() == null ? "" : file.getFileLocation().trim();
            if (name == null || fileLocation.isEmpty()) {
                Logger.getInstance().info("Skipping incomplete file DataSource for service " + serviceName);
                continue;
            }

            String fileName = Paths.get(fileLocation).getFileName().toString();
            String datasetPath = PathConstants.DATASET_BASE_PATH + serviceName + File.separator + fileName;

            registry.register(new DataSourceDefinition(name, dataSourceType, mapAccessMode(file.getMappingType()))
                    .addProperty("filePath", datasetPath)
                    .addProperty("worksheet", file.getSheet())
                    .addProperty("headerRowIndex", 0));

            registered = true;
            Logger.getInstance().info("Registered File DataSource: " + name + " with type " + dataSourceType);
        }

        return registered;
    }

    private DataSourceType mapFileType(String fileType) {
        if (fileType == null) {
            return null;
        }
        switch (fileType.trim().toUpperCase()) {
            case "EXCEL":
                return DataSourceType.EXCEL;
            case "CSV":
                return DataSourceType.CSV;
            case "XML":
                return DataSourceType.CSV;
            default:
                return null;
        }
    }

    /** MappingType replaces the old FetchType element. */
    private AccessMode mapAccessMode(String mappingType) {
        if (mappingType == null || mappingType.isBlank()) {
            return AccessMode.QUERY;
        }
        switch (mappingType.trim().toUpperCase()) {
            case "SEQUENTIAL":
                return AccessMode.SEQUENTIAL;
            case "RANDOM":
                return AccessMode.RANDOM;
            default:
                return AccessMode.QUERY;
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    public boolean startService(String serviceName){
        try{
            AbstractService service = this.getService(serviceName);
            if(service == null){
                Logger.getInstance().error("Service doesn't exist :"+serviceName);
                return false;
            }
            if(!service.isRunning()){
                service.start();
                service.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm:ss a")));
                Logger.getInstance().info("Service started successfully :"+ serviceName);
                Utility.getInstance().updateServiceStatusInDb(service, "Running");
                Utility.getInstance().updateServiceFromCatalogDb(serviceName,"ACTIVE", service.getConfig().getPort());

            }
            return true;

        }catch(Exception e){
            Logger.getInstance().error(serviceName, e);
            e.printStackTrace();
            return false;
        }
    }

    public boolean deleteService(String serviceName){
        try{
            AbstractService service = this.getService(serviceName);
            if(service == null){
                Logger.getInstance().error("Service doesn't exist :"+serviceName);
                return false;
            }

            service.stop();
            services.remove(service.getName(), service);
            //remove from db
            Utility.getInstance().deleteServiceFromDb(service.getName());
            //remove from directory
            CustomMethods.getInstance().removeXmlFile(service.getName());
            //update status incatalog db
            Utility.getInstance().deleteServiceFromCatalogDb(serviceName);
            Logger.getInstance().info("Service deleted successfully :"+ serviceName);
            return true;

        }catch(Exception e){
            Logger.getInstance().error(serviceName, e);
            e.printStackTrace();
        }
        return false;
    }

    public boolean stopService(String serviceName){
        try{
            AbstractService service = this.getService(serviceName);
            if (service == null){
                Logger.getInstance().error("Service doesn't exists :"+serviceName);
                return false;
            }
            if (service.isRunning()){
                service.stop();
                Logger.getInstance().info("Service stopped successfully :"+ serviceName);
                service.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm:ss a")));
                Utility.getInstance().updateServiceStatusInDb(service, "Stopped");
                Utility.getInstance().updateServiceFromCatalogDb(serviceName,"INACTIVE", service.getConfig().getPort());
                return true;
            }
        }catch(Exception e){
            Logger.getInstance().error(serviceName, e);
            e.printStackTrace();
        }

        Logger.getInstance().error("Service is not running :"+ serviceName);
        return false;
    }
}
