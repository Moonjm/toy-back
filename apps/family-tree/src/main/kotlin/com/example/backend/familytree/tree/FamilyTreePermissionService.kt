package com.example.backend.familytree.tree

import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import com.example.backend.user.User
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

@Service
class FamilyTreePermissionService(
    private val familyTreeRepository: FamilyTreeRepository,
    private val memberRepository: FamilyTreeMemberRepository,
) {
    fun findTreeAndCheckPermission(
        treeId: Long,
        user: User,
        requiredRole: FamilyTreeRole,
    ): TreeWithMember {
        val tree =
            familyTreeRepository.findByIdOrNull(treeId)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, treeId)

        val member =
            memberRepository.findByFamilyTreeAndUser(tree, user)
                ?: throw CustomException(ErrorCode.FAMILY_TREE_ACCESS_DENIED)

        if (!member.role.hasAtLeast(requiredRole)) {
            throw CustomException(ErrorCode.FAMILY_TREE_ACCESS_DENIED)
        }

        return TreeWithMember(tree, member)
    }
}
