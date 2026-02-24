package com.example.backend.familytree.person

import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import com.example.backend.familytree.relationship.RelationshipService
import com.example.backend.familytree.tree.FamilyTree
import com.example.backend.familytree.tree.FamilyTreePermissionService
import com.example.backend.familytree.tree.FamilyTreeRole
import com.example.backend.file.FileService
import com.example.backend.user.User
import com.example.backend.user.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class PersonService(
    private val personRepository: PersonRepository,
    private val relationshipService: RelationshipService,
    private val userRepository: UserRepository,
    private val permissionService: FamilyTreePermissionService,
    private val fileService: FileService,
) {
    companion object {
        private fun profilePrefix(treeId: Long) = "profile/$treeId/"
    }

    fun findAllByFamilyTree(tree: FamilyTree): List<Person> = personRepository.findAllByFamilyTree(tree)

    @Transactional
    fun add(
        username: String,
        treeId: Long,
        request: PersonRequest,
    ): Long {
        val user = findUser(username)
        val (tree, _) =
            permissionService.findTreeAndCheckPermission(treeId, user, FamilyTreeRole.EDITOR)

        validateDateRange(request)

        val profileImageId =
            request.profileImageId?.let {
                fileService.attachFile(it, profilePrefix(tree.requiredId))
            }

        val person =
            personRepository.save(
                Person(
                    familyTree = tree,
                    name = request.name,
                    birthDate = request.birthDate,
                    birthDateType = request.birthDateType,
                    deathDate = request.deathDate,
                    gender = request.gender,
                    profileImageId = profileImageId,
                    memo = request.memo,
                ),
            )
        return person.requiredId
    }

    @Transactional
    fun update(
        username: String,
        treeId: Long,
        personId: Long,
        request: PersonRequest,
    ) {
        val user = findUser(username)
        permissionService.findTreeAndCheckPermission(treeId, user, FamilyTreeRole.EDITOR)

        val person =
            personRepository.findByIdOrNull(personId)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, personId)

        validateDateRange(request)

        updateProfileImage(person, request.profileImageId, treeId)

        person.updateDetails(
            name = request.name,
            birthDate = request.birthDate,
            birthDateType = request.birthDateType,
            deathDate = request.deathDate,
            gender = request.gender,
            memo = request.memo,
        )
    }

    @Transactional
    fun delete(
        username: String,
        treeId: Long,
        personId: Long,
    ) {
        val user = findUser(username)
        permissionService.findTreeAndCheckPermission(treeId, user, FamilyTreeRole.EDITOR)

        val person =
            personRepository.findByIdOrNull(personId)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, personId)

        relationshipService.deleteAllByPersonId(personId)
        person.profileImageId?.let { fileService.delete(it) }
        personRepository.delete(person)
    }

    @Transactional
    fun deleteAllByFamilyTree(tree: FamilyTree) {
        val persons = personRepository.findAllByFamilyTree(tree)
        fileService.deleteAll(persons.mapNotNull { it.profileImageId })
        personRepository.deleteAll(persons)
    }

    private fun updateProfileImage(
        person: Person,
        newImageId: Long?,
        treeId: Long,
    ) {
        if (newImageId == person.profileImageId) return
        person.profileImageId?.let { fileService.delete(it) }
        val attachedId = newImageId?.let { fileService.attachFile(it, profilePrefix(treeId)) }
        person.updateProfileImage(attachedId)
    }

    private fun validateDateRange(request: PersonRequest) {
        val birth = request.birthDate ?: return
        val death = request.deathDate ?: return
        if (birth.isAfter(death)) {
            throw CustomException(ErrorCode.INVALID_DATE_RANGE)
        }
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
