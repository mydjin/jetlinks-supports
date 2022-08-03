package org.jetlinks.supports.device.session;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.core.device.session.DeviceSessionEvent;
import org.jetlinks.core.device.session.DeviceSessionInfo;
import org.jetlinks.core.device.session.DeviceSessionManager;
import org.jetlinks.core.server.session.ChildrenDeviceSession;
import org.jetlinks.core.server.session.DeviceSession;
import org.jetlinks.core.utils.Reactors;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
public abstract class AbstractDeviceSessionManager implements DeviceSessionManager {

    protected final Map<String, DeviceSessionRef> localSessions = new ConcurrentHashMap<>(2048);

    private final List<Function<DeviceSessionEvent, Mono<Void>>> sessionEventHandlers = new CopyOnWriteArrayList<>();

    protected final Disposable.Composite disposable = Disposables.composite();

    @Setter
    @Getter
    private Duration sessionLoadTimeout = Duration.ofSeconds(5);

    public abstract String getCurrentServerId();

    protected abstract Mono<Boolean> initSessionConnection(DeviceSession session);

    protected abstract Mono<Long> removeRemoteSession(String deviceId);

    protected abstract Mono<Long> getRemoteTotalSessions();

    protected abstract Mono<Boolean> remoteSessionIsAlive(String deviceId);

    protected abstract Mono<Boolean> checkRemoteSessionIsAlive(String deviceId);

    protected abstract Flux<DeviceSessionInfo> remoteSessions(String serverId);

    public void init() {
        Scheduler scheduler = Schedulers.newSingle("device-session-checker");
        disposable.add(scheduler);
        disposable.add(
                Flux.interval(Duration.ofSeconds(30), scheduler)
                    .concatMap(time -> executeInterval())
                    .subscribe()
        );
    }

    protected Mono<Void> executeInterval() {
        return this
                .checkSession()
                .onErrorResume(err -> Mono.empty());
    }

    public void shutdown() {
        disposable.dispose();
    }

    @Override
    public Mono<DeviceSession> getSession(String deviceId) {
        return getSession(deviceId, true);
    }

    @Override
    public Mono<DeviceSession> getSession(String deviceId,
                                          boolean unregisterWhenNotAlive) {
        if (StringUtils.isEmpty(deviceId)) {
            return Mono.empty();
        }
        DeviceSessionRef ref = localSessions.get(deviceId);
        if (ref == null) {
            return Mono.empty();
        }
        if (unregisterWhenNotAlive) {
            return ref.ref().filterWhen(this::checkSessionAlive);
        }
        return ref.ref();
    }

    @Override
    public Flux<DeviceSession> getSessions() {
        return Flux
                .fromIterable(localSessions.values())
                .flatMap(DeviceSessionRef::ref);
    }

    private Mono<Boolean> checkSessionAlive(String id) {
        DeviceSessionRef ref = localSessions.get(id);
        if (ref == null || ref.loaded == null) {
            return Reactors.ALWAYS_FALSE;
        }
        return checkSessionAlive(ref.loaded);
    }

    private Mono<Boolean> checkSessionAlive(DeviceSession session) {
        if (session == null) {
            return Reactors.ALWAYS_FALSE;
        }
        return session
                .isAliveAsync()
                .defaultIfEmpty(true)
                .flatMap(alive -> {
                    if (!alive) {
                        //移除本地会话
                        return this
                                .removeLocalSession(session)
                                .thenReturn(false);
                    }
                    return Reactors.ALWAYS_TRUE;
                });
    }

    @Override
    public final Mono<Long> remove(String deviceId, boolean onlyLocal) {
        if (onlyLocal) {
            return this.removeLocalSession(deviceId);
        } else {
            return Flux
                    .merge(this.removeLocalSession(deviceId),
                           this.removeRemoteSession(deviceId))
                    .reduce(Math::addExact);
        }
    }

    @Override
    public final Mono<Boolean> isAlive(String deviceId, boolean onlyLocal) {
        Mono<Boolean> localAlive = this
                .getSession(deviceId)
                .hasElement();
        if (onlyLocal) {
            return localAlive;
        }
        return localAlive
                .flatMap(alive -> {
                    if (alive) {
                        return Reactors.ALWAYS_TRUE;
                    }
                    return remoteSessionIsAlive(deviceId);
                });
    }

    @Override
    public Mono<Boolean> checkAlive(String deviceId,
                                    boolean onlyLocal) {
        Mono<Boolean> localAlive = this.checkLocalAlive(deviceId);
        if (onlyLocal) {
            return localAlive;
        }
        return localAlive
                .flatMap(alive -> {
                    if (alive) {
                        return Reactors.ALWAYS_TRUE;
                    }
                    return checkRemoteSessionIsAlive(deviceId);
                });
    }

    protected final Mono<Boolean> checkLocalAlive(String deviceId) {
        return this
                .getSession(deviceId)
                .flatMap(session -> session
                        .getOperator() == null
                        ? Reactors.ALWAYS_FALSE
                        : session
                        .getOperator()
                        .online(getCurrentServerId(),
                                deviceId,
                                session.getClientAddress().map(String::valueOf).orElse(""))
                        .thenReturn(true))
                .defaultIfEmpty(false);
    }

    @Override
    public final Mono<Long> totalSessions(boolean onlyLocal) {
        Mono<Long> total = Mono.just((long) localSessions.size());
        if (onlyLocal) {
            return total;
        }
        return Mono
                .zip(total,
                     getRemoteTotalSessions(),
                     Math::addExact);
    }

    @Override
    public final Flux<DeviceSessionInfo> getSessionInfo() {
        return getSessionInfo(null);
    }

    @Override
    public Flux<DeviceSessionInfo> getSessionInfo(String serverId) {
        return Flux.concat(localSessionInfo(),
                           remoteSessions(serverId));
    }

    protected final Flux<DeviceSessionInfo> localSessionInfo() {
        return Flux
                .fromIterable(localSessions.values())
                .mapNotNull(ref -> ref.loaded)
                .map(session -> DeviceSessionInfo.of(getCurrentServerId(), session));
    }

    @Override
    public Mono<DeviceSession> compute(@Nonnull String deviceId,
                                       Mono<DeviceSession> creator,
                                       Function<DeviceSession, Mono<DeviceSession>> updater) {
        DeviceSessionRef ref = localSessions
                .compute(deviceId, (_id, old) -> {
                    if (old == null) {
                        if (creator == null) {
                            return null;
                        }
                        //创建新会话
                        return new DeviceSessionRef(_id, this, creator);
                    } else {
                        if (updater == null) {
                            return old;
                        }
                        //替换会话
                        old.update(s -> s.flatMap(updater));
                        return old;
                    }
                });
        return ref == null ? Mono.empty() : ref.ref();
    }

    @Override
    public final Mono<DeviceSession> compute(@Nonnull String deviceId,
                                             @Nonnull Function<Mono<DeviceSession>, Mono<DeviceSession>> computer) {

        return localSessions
                .compute(deviceId,
                         (_id, old) -> {
                             if (old != null) {
                                 old.update(computer);
                                 return old;
                             } else {
                                 return new DeviceSessionRef(_id, this, computer.apply(Mono.empty()));
                             }
                         })
                .ref();
    }

    private Mono<DeviceSession> handleSessionCompute0(DeviceSession old,
                                                      DeviceSession newSession) {
        //会话发生了变化,更新一下设备缓存的连接信息
        if (old != null
                && old.isChanged(newSession)
                && newSession.getOperator() != null) {
            log.info("device [{}] session [{}] changed to [{}]", old.getDeviceId(), old, newSession);
            //关闭旧会话
            old.close();
            return newSession
                    .getOperator()
                    .online(getCurrentServerId(), newSession.getId(), newSession
                            .getClientAddress()
                            .map(InetSocketAddress::toString)
                            .orElse(null))
                    .then(
                            handleSessionCompute(old, newSession)
                    );
        }
        return handleSessionCompute(old, newSession);
    }

    protected Mono<DeviceSession> handleSessionCompute(DeviceSession old,
                                                       DeviceSession newSession) {
        return Mono.just(newSession);
    }

    protected final Mono<Void> closeSession(DeviceSession session) {
        try {
            session.close();
        } catch (Throwable ignore) {
        }
        if (session.getOperator() == null) {
            return Mono.empty();
        }
        return this
                //初始化会话连接信息,判断设备是否在其他服务节点还存在连接
                .initSessionConnection(session)
                .flatMap(alive -> {
                    //其他节点存活
                    //或者本地会话存在,可能在离线的同时又上线了?
                    //都认为设备会话依然存活
                    boolean sessionExists = alive || localSessions.containsKey(session.getDeviceId());
                    if (sessionExists) {
                        log.info("device [{}] session [{}] closed,but session still exists!", session.getDeviceId(), session);
                        return fireEvent(DeviceSessionEvent.of(DeviceSessionEvent.Type.unregister, session, true));
                    } else {
                        log.info("device [{}] session [{}] closed", session.getDeviceId(), session);
                        return session
                                .getOperator()
                                .offline()
                                .then(
                                        fireEvent(DeviceSessionEvent.of(DeviceSessionEvent.Type.unregister, session, false))
                                );
                    }

                });
    }

    @SneakyThrows
    private Mono<Long> removeLocalSession(DeviceSession session) {
        DeviceSessionRef ref = localSessions.get(session.getDeviceId());
        if (null == ref) {
            return Reactors.ALWAYS_ZERO_LONG;
        }
        return ref.close(session);

    }

    protected final Mono<Long> removeLocalSession(String deviceId) {
        DeviceSessionRef ref = localSessions.remove(deviceId);
        if (ref != null) {
            return ref.close();
        }
        return Reactors.ALWAYS_ZERO_LONG;
    }

    private Mono<DeviceSession> doRegister(DeviceSession session) {
        if (session.getOperator() == null) {
            return Mono.empty();
        }
        return this
                .remoteSessionIsAlive(session.getDeviceId())
                .flatMap(alive -> session
                        .getOperator()
                        .online(getCurrentServerId(), session.getId(), session
                                .getClientAddress()
                                .map(InetSocketAddress::toString)
                                .orElse(null))
                        .then(fireEvent(DeviceSessionEvent.of(DeviceSessionEvent.Type.register, session, alive))))
                .thenReturn(session);
    }

    protected Mono<Void> fireEvent(DeviceSessionEvent event) {
        if (sessionEventHandlers.isEmpty()) {
            return Mono.empty();
        }
        return Flux
                .fromIterable(sessionEventHandlers)
                .flatMap(handler -> Mono
                        .defer(() -> handler.apply(event))
                        .onErrorResume(err -> {
                            log.error("fire session event error {}", event, err);
                            return Mono.empty();
                        }))
                .then();
    }

    protected Mono<Boolean> doInit(String deviceId) {
        DeviceSessionRef ref = localSessions.get(deviceId);

        if (ref != null && ref.loaded != null && ref.loaded.getOperator() != null) {
            return ref
                    .loaded
                    .getOperator()
                    .online(getCurrentServerId(), null)
                    .thenReturn(true);
        }
        return Mono.empty();
    }

    protected Mono<Long> removeFromCluster(String deviceId) {
        DeviceSessionRef ref = localSessions.remove(deviceId);
        if (ref != null) {
            ref.disposable.dispose();
            DeviceSession session = ref.loaded;
            if (ref.loaded != null) {
                session.close();
                if (session.getOperator() == null) {
                    return Reactors.ALWAYS_ONE_LONG;
                }
                return session
                        .getOperator()
                        .getConnectionServerId()
                        .map(getCurrentServerId()::equals)
                        .defaultIfEmpty(false)
                        .flatMap(sameServer -> {
                            Mono<Void> before = Mono.empty();
                            if (sameServer) {
                                //同一个服务
                                before = session.getOperator().offline().then();
                            }
                            return before
                                    .then(this.fireEvent(DeviceSessionEvent.of(
                                            DeviceSessionEvent.Type.unregister,
                                            session,
                                            !sameServer
                                    )));
                        })
                        .thenReturn(1L);
            }
        }
        return Reactors.ALWAYS_ZERO_LONG;
    }

    @Override
    public Disposable listenEvent(Function<DeviceSessionEvent, Mono<Void>> handler) {
        sessionEventHandlers.add(handler);
        return () -> sessionEventHandlers.remove(handler);
    }

    protected Mono<Void> checkSession() {
        return Flux
                .fromIterable(localSessions.values())
                .filter(ref -> ref.loaded != null)
                .flatMap(ref -> this.checkSessionAlive(ref.loaded))
                .then();
    }

    @SuppressWarnings("rawtypes")
    private final static AtomicReferenceFieldUpdater<DeviceSessionRef, Mono> LOADER
            = AtomicReferenceFieldUpdater.newUpdater(DeviceSessionRef.class, Mono.class, "loader");

    protected static class DeviceSessionRef {
        private final AbstractDeviceSessionManager manager;
        private Sinks.One<DeviceSession> await;
        public final String deviceId;
        public volatile DeviceSession loaded;
        protected volatile Mono<DeviceSession> loader;

        private volatile Disposable disposable;

        private Set<String> children;

        public Set<String> children() {
            return children == null ? children = ConcurrentHashMap.newKeySet() : children;
        }

        public void removeChild(String id) {
            if (children != null) {
                children.remove(id);
            }
        }

        public DeviceSessionRef(String deviceId, AbstractDeviceSessionManager manager, Mono<DeviceSession> ref) {
            this.deviceId = deviceId;
            this.manager = manager;
            update(ref);
        }

        public DeviceSessionRef(String deviceId, AbstractDeviceSessionManager manager, DeviceSession ref) {
            this.deviceId = deviceId;
            this.manager = manager;
            this.loaded = ref;
            this.await = Sinks.one();
            this.await.tryEmitValue(ref);
        }

        public void update(Function<Mono<DeviceSession>, Mono<DeviceSession>> updater) {
            update(updater.apply(Mono.fromSupplier(() -> this.loaded)));
        }

        public void update(Mono<DeviceSession> ref) {
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
            }
            Sinks.One<DeviceSession> old = this.await;
            this.await = Sinks.one();
            if (old != null && old.currentSubscriberCount() > 0) {
                old.tryEmitEmpty();
            }
            loader = ref
                    .flatMap(this::handleLoaded)
                    .timeout(manager.sessionLoadTimeout, Mono.error(() -> new TimeoutException("device [" + deviceId + "] session load timeout")))
                    .switchIfEmpty(Mono.fromRunnable(this::loadEmpty))
                    .doOnError(this::loadError)
                    .doOnNext(this::afterLoaded);
        }

        private Mono<DeviceSession> handleLoaded(DeviceSession session) {
            DeviceSession old = this.loaded;
            this.loaded = session;

            handleParent(parent -> parent.children().add(session.getDeviceId()));

            if (old == null) {
                return manager
                        .doRegister(session)
                        .then(manager.handleSessionCompute0(null, session));
            }
            return manager.handleSessionCompute0(old, session);
        }

        private void afterLoaded(DeviceSession session) {
            if (session != loaded) {
                loaded.close();
            }
            loaded = session;
            this.await.tryEmitValue(session);
        }

        protected void handleParent(Consumer<DeviceSessionRef> parent) {
            if (loaded.isWrapFrom(ChildrenDeviceSession.class)) {
                DeviceSessionRef ref =
                        manager.localSessions
                                .get(
                                        loaded.unwrap(ChildrenDeviceSession.class)
                                              .getParent()
                                              .getDeviceId()
                                );

                if (null != ref) {
                    parent.accept(ref);
                }
            }
        }

        protected Mono<Void> checkChildren() {
            if (children != null) {
                return Flux
                        .fromIterable(children)
                        .flatMap(manager::checkSessionAlive)
                        .then();
            }
            return Mono.empty();
        }

        private Mono<Long> close(DeviceSession session) {
            if (this.loaded == session && manager.localSessions.remove(deviceId, this)) {
                if (disposable != null && !disposable.isDisposed()) {
                    disposable.dispose();
                }
                return doClose(loaded);
            }
            return Reactors.ALWAYS_ZERO_LONG;
        }

        private Mono<Long> close() {
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
            }
            DeviceSession loaded = this.loaded;
            if (loaded != null) {
                return doClose(loaded);
            }
            return Reactors.ALWAYS_ZERO_LONG;
        }

        private Mono<Long> doClose(DeviceSession session) {
            //移除上级设备的子设备信息
            handleParent(ref -> ref.removeChild(session.getDeviceId()));

            return manager
                    .closeSession(session)
                    .then(checkChildren())
                    .then(Reactors.ALWAYS_ONE_LONG);
        }

        private void loadError(Throwable err) {
            if (this.loaded != null) {
                this.loaded.close();
            }
            await.tryEmitError(err);
            manager.localSessions.remove(deviceId, this);
        }

        private void loadEmpty() {
            if (this.loaded != null) {
                this.loaded.close();
            }
            await.tryEmitEmpty();
            manager.localSessions.remove(deviceId, this);
        }

        private void tryLoad(ContextView contextView) {
            @SuppressWarnings("all")
            Mono<DeviceSession> loader = LOADER.getAndSet(this, null);

            if (loader != null) {
                disposable = loader
                        .contextWrite(contextView)
                        .subscribe();
            }
        }

        public Mono<DeviceSession> ref() {
            return Mono.deferContextual(ctx -> {
                tryLoad(ctx);
                return await.asMono();
            });
        }
    }
}
