package com.inviz.visioncerti.doctor;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Resolves {@link DoctorPrincipal} from {@code Authorization: Bearer <session token>} on every request. Dev identity
 * headers are never consulted for physicians.
 */
@Component
public class DoctorPrincipalResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    private final DoctorService doctors;

    public DoctorPrincipalResolver(DoctorService doctors) {
        this.doctors = doctors;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == DoctorPrincipal.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        return doctors.authenticate(request == null ? null : request.getHeader(HttpHeaders.AUTHORIZATION));
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }
}
