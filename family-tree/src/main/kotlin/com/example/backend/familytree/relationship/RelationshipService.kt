package com.example.backend.familytree.relationship

import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import com.example.backend.familytree.person.PersonRepository
import com.example.backend.familytree.tree.FamilyTree
import com.example.backend.familytree.tree.FamilyTreePermissionService
import com.example.backend.familytree.tree.FamilyTreeRole
import com.example.backend.user.User
import com.example.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.LinkedList

@Service
@Transactional(readOnly = true)
class RelationshipService(
    private val spouseRepository: SpouseRepository,
    private val parentChildRepository: ParentChildRepository,
    private val personRepository: PersonRepository,
    private val userRepository: UserRepository,
    private val permissionService: FamilyTreePermissionService,
) {
    companion object {
        private const val MAX_PARENTS = 2
    }

    fun findAllSpousesByFamilyTree(tree: FamilyTree): List<Spouse> = spouseRepository.findAllByFamilyTree(tree)

    fun findAllParentChildByFamilyTree(tree: FamilyTree): List<ParentChild> = parentChildRepository.findAllByFamilyTree(tree)

    @Transactional
    fun addSpouse(
        username: String,
        treeId: Long,
        request: SpouseRequest,
    ): Long {
        val user = findUser(username)
        val (tree, _) =
            permissionService.findTreeAndCheckPermission(treeId, user, FamilyTreeRole.EDITOR)

        if (request.personId == request.spouseId) {
            throw CustomException(ErrorCode.SELF_RELATIONSHIP)
        }

        validatePersonBelongsToTree(request.personId, tree)
        validatePersonBelongsToTree(request.spouseId, tree)

        val personAId = minOf(request.personId, request.spouseId)
        val personBId = maxOf(request.personId, request.spouseId)

        val spouse =
            spouseRepository.save(
                Spouse(familyTree = tree, personAId = personAId, personBId = personBId),
            )
        return spouse.requiredId
    }

    @Transactional
    fun removeSpouse(
        username: String,
        treeId: Long,
        request: SpouseRequest,
    ) {
        val user = findUser(username)
        permissionService.findTreeAndCheckPermission(treeId, user, FamilyTreeRole.EDITOR)

        val personAId = minOf(request.personId, request.spouseId)
        val spouse =
            spouseRepository.findByPersonAIdOrPersonBId(personAId, personAId)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, "spouse")

        spouseRepository.delete(spouse)
    }

    @Transactional
    fun addParentChild(
        username: String,
        treeId: Long,
        request: ParentChildRequest,
    ): Long {
        val user = findUser(username)
        val (tree, _) =
            permissionService.findTreeAndCheckPermission(treeId, user, FamilyTreeRole.EDITOR)

        if (request.parentId == request.childId) {
            throw CustomException(ErrorCode.SELF_RELATIONSHIP)
        }

        validatePersonBelongsToTree(request.parentId, tree)
        validatePersonBelongsToTree(request.childId, tree)

        val parentCount = parentChildRepository.countByChildId(request.childId)
        if (parentCount >= MAX_PARENTS) {
            throw CustomException(ErrorCode.MAX_PARENTS_EXCEEDED)
        }

        val allParentChild = parentChildRepository.findAllByFamilyTree(tree)
        if (isDescendant(request.parentId, request.childId, allParentChild)) {
            throw CustomException(ErrorCode.CIRCULAR_RELATIONSHIP)
        }

        val parentChild =
            parentChildRepository.save(
                ParentChild(
                    familyTree = tree,
                    parentId = request.parentId,
                    childId = request.childId,
                ),
            )
        return parentChild.requiredId
    }

    @Transactional
    fun removeParentChild(
        username: String,
        treeId: Long,
        request: ParentChildRequest,
    ) {
        val user = findUser(username)
        permissionService.findTreeAndCheckPermission(treeId, user, FamilyTreeRole.EDITOR)

        val parentChild =
            parentChildRepository.findByParentIdAndChildId(request.parentId, request.childId)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, "parent-child")

        parentChildRepository.delete(parentChild)
    }

    @Transactional
    fun deleteAllByFamilyTree(tree: FamilyTree) {
        spouseRepository.deleteAllByFamilyTree(tree)
        parentChildRepository.deleteAllByFamilyTree(tree)
    }

    @Transactional
    fun deleteAllByPersonId(personId: Long) {
        spouseRepository.deleteByPersonAIdOrPersonBId(personId, personId)
        parentChildRepository.deleteAllByParentIdOrChildId(personId, personId)
    }

    private fun isDescendant(
        parentId: Long,
        childId: Long,
        allParentChild: List<ParentChild>,
    ): Boolean {
        val childrenMap =
            allParentChild.groupBy { it.parentId }.mapValues { it.value.map { pc -> pc.childId } }
        val queue: LinkedList<Long> = LinkedList()
        queue.add(childId)
        val visited = mutableSetOf(childId)
        while (queue.isNotEmpty()) {
            val current = queue.poll()
            for (child in childrenMap[current] ?: emptyList()) {
                if (child == parentId) return true
                if (visited.add(child)) queue.add(child)
            }
        }
        return false
    }

    private fun validatePersonBelongsToTree(
        personId: Long,
        tree: FamilyTree,
    ) {
        if (!personRepository.existsByIdAndFamilyTree(personId, tree)) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, personId)
        }
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
