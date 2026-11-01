package io.quarkiverse.resiliencytesting.lc4jmcp;

import java.util.ArrayList;
import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import io.quarkiverse.langchain4j.mcp.runtime.McpClientName;

/**
 * The tools of one MCP server, listed and called through its client; a failure of the server is a 503 with the
 * cause, whether the client threw or handed back an error result, as it does for a timeout.
 */
@Path("/tools")
public class ToolsResource {

    private final McpClient weather;

    public ToolsResource(@McpClientName("weather") McpClient weather) {
        this.weather = weather;
    }

    @GET
    public Response list() {
        try {
            List<String> names = new ArrayList<>();
            for (ToolSpecification tool : weather.listTools()) {
                names.add(tool.name());
            }
            return Response.ok(String.join(",", names)).build();
        } catch (RuntimeException e) {
            return Response.status(503).entity(String.valueOf(e)).build();
        }
    }

    @POST
    @Path("/forecast")
    public Response forecast(@QueryParam("city") String city) {
        try {
            ToolExecutionResult result = weather.executeTool(ToolExecutionRequest.builder()
                    .name("forecast")
                    .arguments("{\"city\":\"" + city + "\"}")
                    .build());
            if (result.isError()) {
                return Response.status(503).entity(result.resultText()).build();
            }
            return Response.ok(result.resultText()).build();
        } catch (RuntimeException e) {
            return Response.status(503).entity(String.valueOf(e)).build();
        }
    }
}
