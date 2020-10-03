package org.testcontainers.junit.jqwik;

import lombok.Getter;
import net.jqwik.api.JqwikException;
import net.jqwik.api.lifecycle.AroundContainerHook;
import net.jqwik.api.lifecycle.AroundPropertyHook;
import net.jqwik.api.lifecycle.ContainerLifecycleContext;
import net.jqwik.api.lifecycle.LifecycleContext;
import net.jqwik.api.lifecycle.Lifespan;
import net.jqwik.api.lifecycle.PropertyExecutionResult;
import net.jqwik.api.lifecycle.PropertyExecutor;
import net.jqwik.api.lifecycle.PropertyLifecycleContext;
import net.jqwik.api.lifecycle.SkipExecutionHook;
import net.jqwik.api.lifecycle.Store;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.util.Preconditions;
import org.junit.platform.commons.util.ReflectionUtils;
import org.opentest4j.TestAbortedException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.lifecycle.TestDescription;
import org.testcontainers.lifecycle.TestLifecycleAware;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

class TestcontainersExtension implements AroundPropertyHook, AroundContainerHook, SkipExecutionHook {

    private static final Object IDENTIFIER = TestcontainersExtension.class;
    private static final Object SHARED_LIFECYCLE_AWARE_TEST_CONTAINERS = new Object();

    @Override
    public void beforeContainer(ContainerLifecycleContext context) {
        Class<?> testClass = context.optionalContainerClass().orElseThrow(() -> new IllegalStateException("TestcontainersExtension is only supported for classes."));
        List<StoreAdapter> sharedContainersStoreAdapters = findSharedContainers(testClass);

        Store<HashMap<String, StoreAdapter>> store = getOrCreateContainerClosingStore(IDENTIFIER, Lifespan.RUN, HashMap::new);
        List<TestLifecycleAware> lifecycleAwareContainers = sharedContainersStoreAdapters.stream()
            .peek(adapter -> store.update(storeAdapters -> {
                HashMap<String, StoreAdapter> update = new HashMap<>(storeAdapters);
                update.put(adapter.key, adapter.start());
                return update;
            }))
            .filter(this::isTestLifecycleAware)
            .map(lifecycleAwareAdapter -> (TestLifecycleAware) lifecycleAwareAdapter.container)
            .collect(toList());
        Store.getOrCreate(SHARED_LIFECYCLE_AWARE_TEST_CONTAINERS, Lifespan.RUN, () -> lifecycleAwareContainers);
        signalBeforeTestToContainers(lifecycleAwareContainers, testDescriptionFrom(context));
    }

    @Override
    public void afterContainer(ContainerLifecycleContext context) {
        net.jqwik.api.lifecycle.Store<List<TestLifecycleAware>> containers = Store.getOrCreate(SHARED_LIFECYCLE_AWARE_TEST_CONTAINERS, Lifespan.RUN, ArrayList::new);
        signalAfterTestToContainersFor(containers.get(), testDescriptionFrom(context));
    }

    private Store<HashMap<String, StoreAdapter>> getOrCreateContainerClosingStore(Object identifier, Lifespan lifespan, Supplier<HashMap<String, StoreAdapter>> initializer) {
        Store<HashMap<String, StoreAdapter>> store = Store.getOrCreate(identifier, lifespan, initializer);
        store.onClose(storeAdapters -> storeAdapters.values().forEach(StoreAdapter::close));
        return store;
    }

    @Override
    public int proximity() {
        // must be run before the @BeforeContainer annotation and after @AfterContainer annotation
        return -11;
    }

    @Override
    public PropertyExecutionResult aroundProperty(PropertyLifecycleContext context, PropertyExecutor property) {
        Object testInstance = context.testInstance();
        Store<HashMap<String, StoreAdapter>> store = getOrCreateContainerClosingStore(property.hashCode(), Lifespan.PROPERTY, HashMap::new);
        List<TestLifecycleAware> lifecycleAwareContainers = findRestartContainers(testInstance)
            .peek(adapter -> store.update(storeAdapters -> {
                HashMap<String, StoreAdapter> update = new HashMap<>(storeAdapters);
                update.put(adapter.key, adapter.start());
                return update;
            }))
            .filter(this::isTestLifecycleAware)
            .map(lifecycleAwareAdapter -> (TestLifecycleAware) lifecycleAwareAdapter.container)
            .collect(toList());

        TestDescription testDescription = testDescriptionFrom(context);
        signalBeforeTestToContainers(lifecycleAwareContainers, testDescription);
        PropertyExecutionResult executionResult = property.execute();
        signalAfterTestToContainersFor(lifecycleAwareContainers, testDescription, executionResult);

        return executionResult;
    }

    @Override
    public int aroundPropertyProximity() {
        return -11; // Run before BeforeProperty and after AfterProperty
    }

    private void signalBeforeTestToContainers(List<TestLifecycleAware> lifecycleAwareContainers, TestDescription testDescription) {
        lifecycleAwareContainers.forEach(container -> container.beforeTest(testDescription));
    }

    private void signalAfterTestToContainersFor(List<TestLifecycleAware> containers, TestDescription testDescription){
        containers.forEach(container -> container.afterTest(testDescription, Optional.empty()));
    }

    private void signalAfterTestToContainersFor(List<TestLifecycleAware> containers, TestDescription testDescription, PropertyExecutionResult executionResult) {
        containers.forEach(container -> {
            if(executionResult.status() == PropertyExecutionResult.Status.ABORTED){
                container.afterTest(testDescription, Optional.of(new TestAbortedException()));
            } else {
                container.afterTest(testDescription, executionResult.throwable());
            }
        });
    }

    private TestDescription testDescriptionFrom(LifecycleContext context) {
        return new TestcontainersTestDescription(
            context.label(),
            FilesystemFriendlyNameGenerator.filesystemFriendlyNameOf(context)
        );
    }

    private boolean isTestLifecycleAware(StoreAdapter adapter) {
        return adapter.container instanceof TestLifecycleAware;
    }

    @Override
    public SkipResult shouldBeSkipped(LifecycleContext context) {
        return findTestcontainers(context)
            .map(this::evaluateSkipResult)
            .orElseThrow(() -> new JqwikException("@Testcontainers not found"));
    }

    private Optional<Testcontainers> findTestcontainers(LifecycleContext context) {
        // Find closest TestContainers annotation
        Optional<Testcontainers> first = context.findAnnotationsInContainer(Testcontainers.class).stream().findFirst();
        if(first.isPresent())
            return first;
        else
            return context.findAnnotation(Testcontainers.class);
    }

    private SkipResult evaluateSkipResult(Testcontainers testcontainers) {
        if (testcontainers.disabledWithoutDocker()) {
            if (isDockerAvailable()) {
                return SkipResult.doNotSkip();
            }
            return SkipResult.skip("disabledWithoutDocker is true and Docker is not available");
        }
        return SkipResult.doNotSkip();
    }

    boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    private List<StoreAdapter> findSharedContainers(Class<?> testClass) {
        return ReflectionUtils.findFields(
                testClass,
                isSharedContainer(),
                ReflectionUtils.HierarchyTraversalMode.TOP_DOWN)
            .stream()
            .map(f -> getContainerInstance(null, f))
            .collect(toList());
    }

    private Predicate<Field> isSharedContainer() {
        return isContainer().and(ReflectionUtils::isStatic);
    }

    private Stream<StoreAdapter> findRestartContainers(Object testInstance) {
        return ReflectionUtils.findFields(
                testInstance.getClass(),
                isRestartContainer(),
                ReflectionUtils.HierarchyTraversalMode.TOP_DOWN)
            .stream()
            .map(f -> getContainerInstance(testInstance, f));
    }

    private Predicate<Field> isRestartContainer() {
        return isContainer().and(ReflectionUtils::isNotStatic);
    }

    private static Predicate<Field> isContainer() {
        return field -> {
            boolean isAnnotatedWithContainer = AnnotationSupport.isAnnotated(field, Container.class);
            if (isAnnotatedWithContainer) {
                boolean isStartable = Startable.class.isAssignableFrom(field.getType());

                if (!isStartable) {
                    throw new JqwikException(String.format("FieldName: %s does not implement Startable", field.getName()));
                }
                return true;
            }
            return false;
        };
    }

    private static StoreAdapter getContainerInstance(final Object testInstance, final Field field) {
        try {
            field.setAccessible(true);
            Startable containerInstance = Preconditions.notNull((Startable) field.get(testInstance), "Container " + field.getName() + " needs to be initialized");
            return new StoreAdapter(field.getDeclaringClass(), field.getName(), containerInstance);
        } catch (IllegalAccessException e) {
            throw new JqwikException("Can not access container defined in field " + field.getName());
        }
    }

    /**
     * An adapter for {@link Startable} that implement CloseableResource
     * thereby letting the JUnit automatically stop containers once the current
     * ExtensionContext is closed.
     */
    private static class StoreAdapter {

        @Getter
        private String key;

        private Startable container;

        private StoreAdapter(Class<?> declaringClass, String fieldName, Startable container) {
            this.key = declaringClass.getName() + "." + fieldName;
            this.container = container;
        }

        private StoreAdapter start() {
            container.start();
            return this;
        }

        public void close() {
            container.stop();
        }
    }
}
