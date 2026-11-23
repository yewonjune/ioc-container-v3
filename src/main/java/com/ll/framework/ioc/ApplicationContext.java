package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.Bean;
import com.ll.framework.ioc.annotations.Component;
import com.ll.framework.ioc.annotations.Configuration;
import com.ll.standard.util.Ut;
import org.reflections.Reflections;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.*;

public class ApplicationContext {
    private final String basePackage;

    // 빈 이름 -> 빈 생성 정보 (스캔으로 모은다)
    private final Map<String, BeanDefinition> beanDefinitions = new LinkedHashMap<>();

    // 빈 이름 -> 만들어진 빈 (싱글톤 보관소)
    private final Map<String, Object> beans = new HashMap<>();

    // 지금 만드는 중인 빈 (순환 의존 감지용)
    private final Set<String> beansInCreation = new HashSet<>();

    public ApplicationContext(String basePackage) {
        this.basePackage = basePackage;
    }

    public void init() {
        scan();

        new ArrayList<>(beanDefinitions.keySet()).forEach(this::genBean);
    }

    private void scan() {
        Reflections reflections = new Reflections(basePackage);

        // 1. @Component(및 @Service·@Repository·@Configuration 처럼 @Component 가 붙은 애너테이션)가 붙은 클래스
        reflections.getTypesAnnotatedWith(Component.class)
                .stream()
                .filter(ApplicationContext::isInstantiable)
                .forEach(this::registerComponent);

        // 2. @Configuration 클래스 안의 @Bean 메서드 (메서드 이름이 빈 이름)
        reflections.getTypesAnnotatedWith(Configuration.class)
                .stream()
                .filter(ApplicationContext::isInstantiable)
                .forEach(this::registerBeanMethods);
    }

    private static boolean isInstantiable(Class<?> cls) {
        return !cls.isAnnotation()
                && !cls.isInterface()
                && !Modifier.isAbstract(cls.getModifiers());
    }

    private void registerComponent(Class<?> cls) {
        register(new ComponentBeanDefinition(Ut.str.lcfirst(cls.getSimpleName()), cls));
    }

    private void registerBeanMethods(Class<?> configurationClass) {
        String configurationBeanName = Ut.str.lcfirst(configurationClass.getSimpleName());

        Arrays.stream(configurationClass.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .forEach(method -> register(new MethodBeanDefinition(method.getName(), configurationBeanName, method)));
    }

    private void register(BeanDefinition definition) {
        BeanDefinition old = beanDefinitions.putIfAbsent(definition.name(), definition);

        if (old != null) {
            throw new IllegalStateException("빈 이름이 겹칩니다: " + definition.name());
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T genBean(String beanName) {
        Object bean = beans.get(beanName);
        if (bean != null) return (T) bean;

        BeanDefinition definition = beanDefinitions.get(beanName);
        if (definition == null) throw new IllegalArgumentException("등록되지 않은 빈입니다: " + beanName);

        if (!beansInCreation.add(beanName)) {
            throw new IllegalStateException("순환 의존이 있습니다: " + beanName);
        }

        try {
            bean = definition.create(this);
        } finally {
            beansInCreation.remove(beanName);
        }

        beans.put(beanName, bean);

        return (T) bean;
    }

    // 생성자·@Bean 메서드의 파라미터를 빈으로 채운다
    private Object[] resolveArguments(Executable executable) {
        return Arrays.stream(executable.getParameters())
                .map(parameter -> genBean(beanNameFor(parameter)))
                .toArray();
    }

    // 파라미터 타입에 맞는 빈이 하나면 그것을, 여러 개면 파라미터 이름과 같은 빈을 고른다
    private String beanNameFor(Parameter parameter) {
        Class<?> type = parameter.getType();

        List<String> candidates = beanDefinitions.values()
                .stream()
                .filter(definition -> type.isAssignableFrom(definition.type()))
                .map(BeanDefinition::name)
                .toList();

        if (candidates.size() == 1) return candidates.getFirst();

        if (parameter.isNamePresent() && candidates.contains(parameter.getName())) {
            return parameter.getName();
        }

        throw new IllegalStateException(
                "%s 타입의 빈을 하나로 정할 수 없습니다: %s".formatted(type.getName(), candidates)
        );
    }

    private static Object invoke(Executable executable, Object target, Object[] args) {
        try {
            executable.setAccessible(true);

            if (executable instanceof Constructor<?> constructor) return constructor.newInstance(args);
            return ((Method) executable).invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(executable + " 로 빈 생성 실패", e);
        }
    }

    private sealed interface BeanDefinition permits ComponentBeanDefinition, MethodBeanDefinition {
        String name();

        Class<?> type();

        Object create(ApplicationContext context);
    }

    // 컴포넌트 클래스: 파라미터가 가장 많은 생성자로 만든다
    private record ComponentBeanDefinition(String name, Class<?> type) implements BeanDefinition {
        @Override
        public Object create(ApplicationContext context) {
            Constructor<?> constructor = Arrays.stream(type.getDeclaredConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow();

            return invoke(constructor, null, context.resolveArguments(constructor));
        }
    }

    // @Bean 메서드: 설정 클래스 빈에서 메서드를 호출해 반환값을 빈으로 쓴다
    private record MethodBeanDefinition(String name, String configurationBeanName, Method method)
            implements BeanDefinition {
        @Override
        public Class<?> type() {
            return method.getReturnType();
        }

        @Override
        public Object create(ApplicationContext context) {
            Object configuration = Modifier.isStatic(method.getModifiers())
                    ? null
                    : context.genBean(configurationBeanName);

            return invoke(method, configuration, context.resolveArguments(method));
        }
    }
}
