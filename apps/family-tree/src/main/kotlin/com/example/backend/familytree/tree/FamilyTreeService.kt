package com.example.backend.familytree.tree

import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import com.example.backend.familytree.person.PersonService
import com.example.backend.familytree.person.toResponse
import com.example.backend.familytree.relationship.RelationshipService
import com.example.backend.familytree.relationship.toResponse
import com.example.backend.file.FileService
import com.example.backend.user.User
import com.example.backend.user.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class FamilyTreeService(
    private val familyTreeRepository: FamilyTreeRepository,
    private val memberRepository: FamilyTreeMemberRepository,
    private val userRepository: UserRepository,
    private val permissionService: FamilyTreePermissionService,
    private val fileService: FileService,
    private val personService: PersonService,
    private val relationshipService: RelationshipService,
) {
    @Transactional
    fun create(
        username: String,
        request: FamilyTreeRequest,
    ): Long {
        val user = findUser(username)
        val tree =
            familyTreeRepository.save(
                FamilyTree(name = request.name, description = request.description),
            )
        memberRepository.save(
            FamilyTreeMember(
                familyTree = tree,
                user = user,
                role = FamilyTreeRole.OWNER,
            ),
        )
        return tree.requiredId
    }

    fun list(username: String): List<FamilyTreeListResponse> {
        val user = findUser(username)
        return memberRepository.findAllByUser(user).map {
            FamilyTreeListResponse(
                id = it.familyTree.requiredId,
                name = it.familyTree.name,
                description = it.familyTree.description,
                myRole = it.role,
            )
        }
    }

    fun getDetail(
        username: String,
        treeId: Long,
    ): FamilyTreeDetailResponse {
        val user = findUser(username)
        val (tree, member) =
            permissionService.findTreeAndCheckPermission(treeId, user, FamilyTreeRole.VIEWER)

        val persons = personService.findAllByFamilyTree(tree)
        val spouses = relationshipService.findAllSpousesByFamilyTree(tree)
        val parentChildList = relationshipService.findAllParentChildByFamilyTree(tree)

        val profileImageUrls = fileService.getPresignedUrls(persons.mapNotNull { it.profileImageId })
        return FamilyTreeDetailResponse(
            id = tree.requiredId,
            name = tree.name,
            description = tree.description,
            myRole = member.role,
            persons = persons.map { it.toResponse(profileImageUrl = profileImageUrls[it.profileImageId]) },
            spouses = spouses.map { it.toResponse() },
            parentChild = parentChildList.map { it.toResponse() },
        )
    }

    @Transactional
    fun update(
        username: String,
        treeId: Long,
        request: FamilyTreeRequest,
    ) {
        val user = findUser(username)
        val (tree, _) =
            permissionService.findTreeAndCheckPermission(treeId, user, FamilyTreeRole.OWNER)
        tree.updateDetails(name = request.name, description = request.description)
    }

    @Transactional
    fun delete(
        username: String,
        treeId: Long,
    ) {
        val user = findUser(username)
        val (tree, _) =
            permissionService.findTreeAndCheckPermission(treeId, user, FamilyTreeRole.OWNER)

        relationshipService.deleteAllByFamilyTree(tree)
        personService.deleteAllByFamilyTree(tree)
        memberRepository.deleteAll(memberRepository.findAllByFamilyTree(tree))
        familyTreeRepository.delete(tree)
    }

    @Transactional
    fun addMember(
        username: String,
        treeId: Long,
        request: MemberRequest,
    ): Long {
        val user = findUser(username)
        val (tree, _) =
            permissionService.findTreeAndCheckPermission(treeId, user, FamilyTreeRole.OWNER)

        val targetUser =
            userRepository.findByIdOrNull(request.userId)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, request.userId)

        val member =
            memberRepository.save(
                FamilyTreeMember(
                    familyTree = tree,
                    user = targetUser,
                    role = request.role,
                ),
            )
        return member.requiredId
    }

    fun listMembers(
        username: String,
        treeId: Long,
    ): List<MemberResponse> {
        val user = findUser(username)
        val (tree, _) =
            permissionService.findTreeAndCheckPermission(treeId, user, FamilyTreeRole.VIEWER)
        return memberRepository.findAllByFamilyTree(tree).map { it.toResponse() }
    }

    @Transactional
    fun updateMemberRole(
        username: String,
        treeId: Long,
        memberId: Long,
        request: MemberRoleRequest,
    ) {
        val user = findUser(username)
        permissionService.findTreeAndCheckPermission(treeId, user, FamilyTreeRole.OWNER)

        val member =
            memberRepository.findByIdOrNull(memberId)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, memberId)

        member.updateRole(request.role)
    }

    @Transactional
    fun removeMember(
        username: String,
        treeId: Long,
        memberId: Long,
    ) {
        val user = findUser(username)
        val (tree, _) =
            permissionService.findTreeAndCheckPermission(treeId, user, FamilyTreeRole.OWNER)

        val member =
            memberRepository.findByIdOrNull(memberId)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, memberId)

        if (member.role == FamilyTreeRole.OWNER) {
            val ownerCount = memberRepository.countByFamilyTreeAndRole(tree, FamilyTreeRole.OWNER)
            if (ownerCount <= 1) {
                throw CustomException(ErrorCode.FAMILY_TREE_LAST_OWNER)
            }
        }

        memberRepository.delete(member)
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
