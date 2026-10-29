package com.leo.blockapp.domain.ports.input

import com.leo.blockapp.domain.model.DeletedBlock
import com.leo.blockapp.domain.model.RestoreBlockResult

/**
 * Input port: bring back a deleted block next to its original neighbors, keeping only the apps that are still free.
 * Restoring a block whose id already exists writes nothing and never overwrites it.
 */
interface RestoreBlock {
    suspend operator fun invoke(deletedBlock: DeletedBlock): RestoreBlockResult
}
