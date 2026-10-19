package com.inviz.visioncerti.security;

import com.inviz.visioncerti.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Injects the {@link RequestActor} bound by the active authentication filter into controller methods.
 * Fails closed with 401 when no filter bound an actor.
 */
@Component
public class RequestActorResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    private static final String ATTRIBUTE = RequestActor.class.getName();

    static void bind(HttpServletRequest request, RequestActor actor) {
        request.setAttribute(ATTRIBUTE, actor);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == RequestActor.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object actor = webRequest.getAttribute(ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST);
        if (actor instanceof RequestActor requestActor) {
            return requestActor;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "No authenticated actor");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }
}
