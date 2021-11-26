package org.jetlinks.supports.scalecube;

import io.scalecube.cluster.ClusterConfig;
import io.scalecube.cluster.ClusterImpl;
import io.scalecube.cluster.ClusterMessageHandler;
import io.scalecube.cluster.Member;
import io.scalecube.cluster.membership.MembershipEvent;
import io.scalecube.cluster.transport.api.Message;
import io.scalecube.net.Address;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

@Slf4j
public class ExtendedClusterImpl implements ExtendedCluster {

    private final ClusterImpl real;
    private final Sinks.Many<Message> messageSink = Sinks.many().multicast().directBestEffort();
    private final Sinks.Many<Message> gossipSink = Sinks.many().multicast().directBestEffort();
    private final Sinks.Many<MembershipEvent> membershipEvents = Sinks.many().multicast().directBestEffort();

    private final List<ClusterMessageHandler> handlers = new CopyOnWriteArrayList<>();

    public ExtendedClusterImpl(ClusterConfig config) {
        this(new ClusterImpl(config));
    }

    public ExtendedClusterImpl(ClusterImpl impl) {
        real = impl
                .handler(cluster -> new ClusterMessageHandlerDispatcher());
    }

    class ClusterMessageHandlerDispatcher implements ClusterMessageHandler {
        @Override
        public void onMessage(Message message) {
            messageSink.tryEmitNext(message);
            doHandler(message, ClusterMessageHandler::onMessage);
        }

        @Override
        public void onGossip(Message gossip) {
            messageSink.tryEmitNext(gossip);
            doHandler(gossip, ClusterMessageHandler::onGossip);
        }

        @Override
        public void onMembershipEvent(MembershipEvent event) {
            membershipEvents.tryEmitNext(event);
            doHandler(event, ClusterMessageHandler::onMembershipEvent);
        }
    }

    private <T> void doHandler(T e, BiConsumer<ClusterMessageHandler, T> consumer) {
        for (ClusterMessageHandler handler : handlers) {
            consumer.accept(handler, e);
        }
    }

    public ExtendedClusterImpl handler(Function<ExtendedCluster, ClusterMessageHandler> handlerFunction) {
        handlers.add(handlerFunction.apply(this));
        return this;
    }

    public Mono<ExtendedCluster> start() {
        return real
                .start()
                .thenReturn(this);
    }

    public ExtendedCluster startAwait() {
        real.startAwait();
        return this;
    }

    @Override
    public Flux<MembershipEvent> listenMembership() {
        return membershipEvents.asFlux();
    }

    @Override
    public Disposable listenMessage(@Nonnull String qualifier, BiFunction<Message, ExtendedCluster, Mono<Void>> handler) {
        return messageSink
                .asFlux()
                .filter(msg -> Objects.equals(qualifier, msg.qualifier()))
                .flatMap(msg -> handler.apply(msg, this)
                                       .onErrorResume(err -> {
                                           log.error(err.getMessage(), err);
                                           return Mono.empty();
                                       }))
                .subscribe();
    }

    @Override
    public Disposable listenGossip(@Nonnull String qualifier, BiFunction<Message, ExtendedCluster, Mono<Void>> handler) {
        return gossipSink
                .asFlux()
                .filter(msg -> Objects.equals(qualifier, msg.qualifier()))
                .flatMap(msg -> handler.apply(msg, this)
                                       .onErrorResume(err -> {
                                           log.error(err.getMessage(), err);
                                           return Mono.empty();
                                       }))
                .subscribe();
    }

    @Override
    public Address address() {
        return real.address();
    }

    @Override
    public Mono<Void> send(Member member, Message message) {
        return real.send(member, message);
    }

    @Override
    public Mono<Void> send(Address address, Message message) {
        return real.send(address, message);
    }

    @Override
    public Mono<Message> requestResponse(Address address, Message request) {
        return real.requestResponse(address, request);
    }

    @Override
    public Mono<Message> requestResponse(Member member, Message request) {
        return real.requestResponse(member, request);
    }

    @Override
    public Mono<String> spreadGossip(Message message) {
        return real.spreadGossip(message);
    }

    @Override
    public <T> Optional<T> metadata() {
        return real.metadata();
    }

    @Override
    public <T> Optional<T> metadata(Member member) {
        return real.metadata(member);
    }

    @Override
    public Member member() {
        return real.member();
    }

    @Override
    public Optional<Member> member(String id) {
        return real.member(id);
    }

    @Override
    public Optional<Member> member(Address address) {
        return real.member(address);
    }

    @Override
    public Collection<Member> members() {
        return real.members();
    }

    @Override
    public Collection<Member> otherMembers() {
        return real.otherMembers();
    }

    @Override
    public <T> Mono<Void> updateMetadata(T metadata) {
        return real.updateMetadata(metadata);
    }

    @Override
    public void shutdown() {
        real.shutdown();
    }

    @Override
    public Mono<Void> onShutdown() {
        return real.onShutdown();
    }

    @Override
    public boolean isShutdown() {
        return real.isShutdown();
    }
}