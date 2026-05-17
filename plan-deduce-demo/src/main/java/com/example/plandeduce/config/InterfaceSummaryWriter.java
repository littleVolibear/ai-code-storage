package com.example.plandeduce.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 项目接口汇总生成器。
 * 应用启动后自动扫描 Spring MVC 对外 HTTP 接口，并将接口信息写入项目根目录 dd.txt。
 */
@Component
public class InterfaceSummaryWriter implements ApplicationRunner {
    private final RequestMappingInfoHandlerMapping requestMappingHandlerMapping;

    public InterfaceSummaryWriter(RequestMappingInfoHandlerMapping requestMappingHandlerMapping) {
        this.requestMappingHandlerMapping = requestMappingHandlerMapping;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        writeInterfaceSummary();
    }

    /**
     * 扫描项目接口并写入 dd.txt。
     */
    public void writeInterfaceSummary() throws IOException {
        List<ApiInfo> apiInfoList = collectHttpApiInfo();
        apiInfoList.sort(Comparator
                .comparing(ApiInfo::getPath)
                .thenComparing(ApiInfo::getMethods)
                .thenComparing(ApiInfo::getHandlerName));

        List<String> lines = new ArrayList<>();
        lines.add("项目接口信息汇总");
        lines.add("");
        lines.add("说明：本文件由项目启动时自动生成，汇总当前 Spring Boot 应用中对外暴露的 HTTP 接口和 WebSocket 入口。");
        lines.add("");

        int index = 1;
        for (ApiInfo apiInfo : apiInfoList) {
            lines.add(index + ". " + apiInfo.getMethods() + " " + apiInfo.getPath());
            lines.add("处理方法: " + apiInfo.getHandlerName());
            lines.add("控制器: " + apiInfo.getControllerName());
            lines.add("路径参数: " + apiInfo.getPathParams());
            lines.add("查询参数: " + apiInfo.getQueryParams());
            lines.add("请求体: " + apiInfo.getRequestBody());
            lines.add("返回类型: " + apiInfo.getReturnType());
            lines.add("");
            index++;
        }

        lines.add(index + ". WEBSOCKET /ws/planDeduce");
        lines.add("处理组件: PlanDeduceWebSocketHandler");
        lines.add("说明: 进度条推演 WebSocket 推送入口，HTTP 接口负责发送控制命令，WebSocket 负责向指定 sessionId 推送状态和数据。");
        lines.add("连接参数: sessionId: String，非必填，默认值=default");
        lines.add("主要消息类型: INIT、PLAY、PAUSE、START、SPEED、SKIP、INTERVAL、FINISH、DESTROY、ERROR");
        lines.add("");

        Files.write(Paths.get("dd.txt"), lines, StandardCharsets.UTF_8);
    }

    private List<ApiInfo> collectHttpApiInfo() {
        List<ApiInfo> apiInfoList = new ArrayList<>();
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = requestMappingHandlerMapping.getHandlerMethods();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            RequestMappingInfo mappingInfo = entry.getKey();
            HandlerMethod handlerMethod = entry.getValue();

            Set<String> paths = extractPaths(mappingInfo);
            if (paths.isEmpty()) {
                continue;
            }

            String methods = extractMethods(mappingInfo);
            Method method = handlerMethod.getMethod();

            for (String path : paths) {
                if (isInternalSpringEndpoint(path)) {
                    continue;
                }

                ApiInfo apiInfo = new ApiInfo();
                apiInfo.setMethods(methods);
                apiInfo.setPath(path);
                apiInfo.setHandlerName(method.getName());
                apiInfo.setControllerName(handlerMethod.getBeanType().getSimpleName());
                apiInfo.setPathParams(extractPathParams(path));
                apiInfo.setQueryParams(extractQueryParams(method));
                apiInfo.setRequestBody(extractRequestBody(method));
                apiInfo.setReturnType(method.getReturnType().getSimpleName());
                apiInfoList.add(apiInfo);
            }
        }

        return apiInfoList;
    }

    private Set<String> extractPaths(RequestMappingInfo mappingInfo) {
        if (mappingInfo.getPathPatternsCondition() != null) {
            return mappingInfo.getPathPatternsCondition().getPatternValues();
        }
        if (mappingInfo.getPatternsCondition() != null) {
            return mappingInfo.getPatternsCondition().getPatterns();
        }
        return java.util.Collections.emptySet();
    }

    private String extractMethods(RequestMappingInfo mappingInfo) {
        if (mappingInfo.getMethodsCondition() == null || mappingInfo.getMethodsCondition().getMethods().isEmpty()) {
            return "ANY";
        }

        List<String> methods = new ArrayList<>();
        mappingInfo.getMethodsCondition().getMethods().forEach(method -> methods.add(method.name()));
        methods.sort(String::compareTo);
        return String.join(", ", methods);
    }

    private boolean isInternalSpringEndpoint(String path) {
        return path == null
                || path.startsWith("/error")
                || path.startsWith("/actuator");
    }

    private String extractPathParams(String path) {
        List<String> params = new ArrayList<>();
        int searchIndex = 0;
        while (searchIndex < path.length()) {
            int start = path.indexOf('{', searchIndex);
            if (start < 0) {
                break;
            }
            int end = path.indexOf('}', start);
            if (end < 0) {
                break;
            }
            String paramName = path.substring(start + 1, end);
            params.add(paramName + ": String，必填");
            searchIndex = end + 1;
        }
        return params.isEmpty() ? "无" : String.join("；", params);
    }

    private String extractQueryParams(Method method) {
        List<String> params = new ArrayList<>();

        for (Parameter parameter : method.getParameters()) {
            RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
            if (requestParam == null) {
                continue;
            }

            String name = requestParam.name();
            if (name == null || name.isEmpty()) {
                name = requestParam.value();
            }
            if (name == null || name.isEmpty()) {
                name = parameter.getName();
            }

            String requiredText = requestParam.required() ? "必填" : "非必填";
            String defaultValue = requestParam.defaultValue();

            if (defaultValue != null && !defaultValue.contains("DEFAULT_NONE")) {
                params.add(name + ": " + parameter.getType().getSimpleName() + "，非必填，默认值=" + defaultValue);
            } else {
                params.add(name + ": " + parameter.getType().getSimpleName() + "，" + requiredText);
            }
        }

        return params.isEmpty() ? "无" : String.join("；", params);
    }

    private String extractRequestBody(Method method) {
        for (Parameter parameter : method.getParameters()) {
            for (Annotation annotation : parameter.getAnnotations()) {
                if (annotation.annotationType().equals(RequestBody.class)) {
                    return parameter.getType().getSimpleName();
                }
            }
        }
        return "无";
    }

    private static class ApiInfo {
        private String methods;
        private String path;
        private String handlerName;
        private String controllerName;
        private String pathParams;
        private String queryParams;
        private String requestBody;
        private String returnType;

        public String getMethods() {
            return methods;
        }

        public void setMethods(String methods) {
            this.methods = methods;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getHandlerName() {
            return handlerName;
        }

        public void setHandlerName(String handlerName) {
            this.handlerName = handlerName;
        }

        public String getControllerName() {
            return controllerName;
        }

        public void setControllerName(String controllerName) {
            this.controllerName = controllerName;
        }

        public String getPathParams() {
            return pathParams;
        }

        public void setPathParams(String pathParams) {
            this.pathParams = pathParams;
        }

        public String getQueryParams() {
            return queryParams;
        }

        public void setQueryParams(String queryParams) {
            this.queryParams = queryParams;
        }

        public String getRequestBody() {
            return requestBody;
        }

        public void setRequestBody(String requestBody) {
            this.requestBody = requestBody;
        }

        public String getReturnType() {
            return returnType;
        }

        public void setReturnType(String returnType) {
            this.returnType = returnType;
        }
    }
}