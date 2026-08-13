package org.fiware.consent.tmforum;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Reactive plumbing shared by the {@link TMForumApis} implementations: turning a backend
 * {@code 404} into an empty result and a nullable list body into a {@link Flux}.
 */
final class TMForumResponses {

    private TMForumResponses() {
    }

    /**
     * Maps a {@code 404} response error to an empty {@link Mono} (a missing resource), and rethrows
     * anything else.
     *
     * @param throwable the error raised by the client
     * @param <T>       the (irrelevant) element type
     * @return an empty {@link Mono} on {@code 404}, otherwise a {@link Mono} erroring with {@code throwable}
     */
    static <T> Mono<T> emptyOnNotFound(Throwable throwable) {
        if (throwable instanceof HttpClientResponseException responseException
                && responseException.getStatus() == HttpStatus.NOT_FOUND) {
            return Mono.empty();
        }
        return Mono.error(throwable);
    }

    /**
     * Emits the elements of a (possibly {@code null}) list body.
     *
     * @param body the list body, or {@code null}
     * @param <T>  the element type
     * @return a {@link Flux} over {@code body}, or an empty {@link Flux} if it is {@code null}
     */
    static <T> Flux<T> fluxFromNullable(List<T> body) {
        return body == null ? Flux.empty() : Flux.fromIterable(body);
    }
}
