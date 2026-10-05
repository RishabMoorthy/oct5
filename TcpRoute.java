package org.framework.services.tcp;

import org.framework.core.AbstractService;
import org.framework.core.BaseRoute;
import org.framework.core.ResponseGenerator;
import org.framework.core.ServerManager;
import org.framework.properties.Context;
import org.framework.properties.MockRequest;
import org.framework.properties.MockResponse;
import org.framework.utils.AddCustomResponseToOperation;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.framework.constants.PathConstants.VS_XML_DIRECTORY;
import static org.framework.constants.PathConstants.VS_FILE_EXTENSION;

public class TcpRoute extends BaseRoute {
    String operationName;

    public String getOperationName() {
        return operationName;
    }

    public void setOperationName(String operationName) {
        this.operationName = operationName;
    }

    public TcpRoute(String serviceName, ResponseGenerator generator, List<MockResponse> responses,
            String defaultResponse, String name, String dispatchStyle, String contentType) {
        super(serviceName, generator, responses, defaultResponse, name, dispatchStyle, contentType);
        this.operationName = name;
    }

    @Override
    public boolean matchRequest(Context context, MockRequest request) {
        if (super.getName().equals(request.getOperation()))
            return true;
        return false;
    }

    public MockResponse addNewMockResponse(String mockResponseName) {

        MockResponse response = new MockResponse(mockResponseName, "".getBytes(StandardCharsets.UTF_8), null,
                this.operationName, this.getServiceName());
        super.getResponses().add(response);
        // add new mock response in xml
        addNewMockResponseInXml(response);
        return response;
    }

    public void addNewMockResponseInXml(MockResponse response) {
        System.out.println("service name : " + getServiceName());
        AbstractService service = ServerManager.getInstance().getServices().get(getServiceName());
        AddCustomResponseToOperation add = new AddCustomResponseToOperation();

        String updatedXml = add.addMockResponseInXml(operationName, response.getName(),
                VS_XML_DIRECTORY + "/" + getServiceName() + VS_FILE_EXTENSION);
        service.getConfig().setXmlFileContent(updatedXml);
    }

    public MockResponse getMockResponseByName(String mockResponseName) {
        List<MockResponse> mockResponses = super.getResponses();
        for (int i = 0; i < mockResponses.size(); i++) {
            if (mockResponses.get(i).getName().equals(mockResponseName)) {
                return mockResponses.get(i);
            }
        }

        return null;
    }
}
