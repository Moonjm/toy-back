package com.example.backend.familytree.tree

import com.example.backend.familytree.person.PersonResponse
import com.example.backend.familytree.relationship.ParentChildResponse
import com.example.backend.familytree.relationship.SpouseResponse
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class TreeWithMember(
    val tree: FamilyTree,
    val member: FamilyTreeMember,
)

@Schema(description = "가계도 요청")
data class FamilyTreeRequest(
    @field:Schema(description = "이름", example = "김씨 가계도")
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
    @field:Schema(description = "설명", example = "본관: 김해")
    @field:Size(max = 500)
    val description: String? = null,
)

@Schema(description = "가계도 목록 응답")
data class FamilyTreeListResponse(
    @field:Schema(description = "ID", example = "1")
    val id: Long,
    @field:Schema(description = "이름", example = "김씨 가계도")
    val name: String,
    @field:Schema(description = "설명")
    val description: String?,
    @field:Schema(description = "내 역할")
    val myRole: FamilyTreeRole,
)

@Schema(description = "가계도 상세 응답")
data class FamilyTreeDetailResponse(
    @field:Schema(description = "ID", example = "1")
    val id: Long,
    @field:Schema(description = "이름")
    val name: String,
    @field:Schema(description = "설명")
    val description: String?,
    @field:Schema(description = "내 역할")
    val myRole: FamilyTreeRole,
    @field:Schema(description = "인물 목록")
    val persons: List<PersonResponse>,
    @field:Schema(description = "배우자 관계 목록")
    val spouses: List<SpouseResponse>,
    @field:Schema(description = "부모-자식 관계 목록")
    val parentChild: List<ParentChildResponse>,
)

@Schema(description = "멤버 추가 요청")
data class MemberRequest(
    @field:Schema(description = "사용자 ID", example = "2")
    val userId: Long,
    @field:Schema(description = "역할", example = "EDITOR")
    val role: FamilyTreeRole,
)

@Schema(description = "멤버 역할 변경 요청")
data class MemberRoleRequest(
    @field:Schema(description = "역할", example = "VIEWER")
    val role: FamilyTreeRole,
)

@Schema(description = "멤버 응답")
data class MemberResponse(
    @field:Schema(description = "멤버 ID", example = "1")
    val id: Long,
    @field:Schema(description = "사용자 ID", example = "2")
    val userId: Long,
    @field:Schema(description = "사용자 이름", example = "홍길동")
    val userName: String,
    @field:Schema(description = "역할")
    val role: FamilyTreeRole,
)

fun FamilyTreeMember.toResponse(): MemberResponse =
    MemberResponse(
        id = requiredId,
        userId = user.requiredId,
        userName = user.name,
        role = role,
    )
