package com.leo.blockapp.domain.ports.input

import kotlinx.coroutines.flow.Flow

/**
 * Input port: the packages that must be evaluated when they come to the foreground, emitting again whenever that
 * set changes. It is the packages of the enabled blocks plus those of every active hold, so apps removed from a
 * block, moved, or whose block was paused or deleted stay enforced until the hold ends.
 *
 * The first emission may be delayed during storage outages (it waits for both config and states). Consumers must not
 * treat a missing emission as "nothing to enforce".
 */
interface ObserveEnforcedPackages {
    operator fun invoke(): Flow<Set<String>>
}
