package com.toy.backend.file

import com.toy.backend.common.entity.withId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import java.time.LocalDateTime

private fun expiredFile(
    id: Long,
    storedName: String,
) = FileEntity(
    originalName = "photo.png",
    storedName = storedName,
    contentType = "image/png",
    fileSize = 12L,
    bucketName = "test-bucket",
).withId(id)

class FileCleanupServiceTest :
    BehaviorSpec({
        val s3Client = mockk<S3Client>()
        val repository = mockk<FileRepository>()
        val service = FileCleanupService(s3Client, repository)

        val cutoff = LocalDateTime.of(2026, 7, 26, 4, 0)

        Given("TTL 을 넘긴 temp 파일이 있으면") {
            val expired = expiredFile(1L, "temp/abcd1234.png")
            every {
                repository.findAllByStatusAndUpdatedAtBefore(FileStatus.TEMP, cutoff)
            } returns listOf(expired)

            When("정리하면") {
                every { s3Client.deleteObject(any<DeleteObjectRequest>()) } returns mockk()
                justRun { repository.deleteByIdInAndStatus(listOf(1L), FileStatus.TEMP) }

                val purged = service.purgeExpiredTempFiles(cutoff)

                Then("S3 객체와 레코드를 함께 지운다") {
                    purged shouldBe 1
                    verify {
                        s3Client.deleteObject(match<DeleteObjectRequest> { it.key() == "temp/abcd1234.png" })
                        repository.deleteByIdInAndStatus(listOf(1L), FileStatus.TEMP)
                    }
                }
            }
        }

        Given("S3 삭제가 실패하는 파일이 섞여 있으면") {
            val ok = expiredFile(1L, "temp/ok.png")
            val failing = expiredFile(2L, "temp/failing.png")
            every {
                repository.findAllByStatusAndUpdatedAtBefore(FileStatus.TEMP, cutoff)
            } returns listOf(ok, failing)

            When("정리하면") {
                every {
                    s3Client.deleteObject(match<DeleteObjectRequest> { it.key() == "temp/ok.png" })
                } returns mockk()
                every {
                    s3Client.deleteObject(match<DeleteObjectRequest> { it.key() == "temp/failing.png" })
                } throws RuntimeException("S3 unavailable")
                justRun { repository.deleteByIdInAndStatus(listOf(1L), FileStatus.TEMP) }

                val purged = service.purgeExpiredTempFiles(cutoff)

                Then("실패한 건의 레코드는 남겨 다음 주기에 재시도한다") {
                    purged shouldBe 1
                    verify { repository.deleteByIdInAndStatus(listOf(1L), FileStatus.TEMP) }
                }
            }
        }

        Given("정리 대상이 없으면") {
            every {
                repository.findAllByStatusAndUpdatedAtBefore(FileStatus.TEMP, cutoff)
            } returns emptyList()

            When("정리하면") {
                val purged = service.purgeExpiredTempFiles(cutoff)

                Then("아무것도 지우지 않는다") {
                    purged shouldBe 0
                    verify(exactly = 0) { repository.deleteByIdInAndStatus(any(), any()) }
                }
            }
        }
    })
