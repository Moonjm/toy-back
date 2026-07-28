package com.toy.backend.file

import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner

private fun tempFile(
    storedName: String = "temp/abcd1234.png",
    status: FileStatus = FileStatus.TEMP,
) = FileEntity(
    originalName = "photo.png",
    storedName = storedName,
    contentType = "image/png",
    fileSize = 12L,
    bucketName = "test-bucket",
    status = status,
)

class FileServiceTest :
    BehaviorSpec({
        val s3Client = mockk<S3Client>()
        val s3Presigner = mockk<S3Presigner>()
        val repository = mockk<FileRepository>()
        val properties =
            S3Properties(
                endpoint = "http://localhost:9000",
                accessKey = "access",
                secretKey = "secret",
                bucket = "test-bucket",
            )
        val service = FileService(s3Client, s3Presigner, repository, properties)

        afterTest {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization()
            }
        }

        Given("업로드") {
            When("파일명에 경로 구분자가 섞여 있으면") {
                val putRequest = slot<PutObjectRequest>()
                val saved = slot<FileEntity>()
                every { s3Client.putObject(capture(putRequest), any<RequestBody>()) } returns mockk()
                every { repository.save(capture(saved)) } answers { saved.captured.withId(1L) }

                service.upload(
                    MockMultipartFile("file", "../../etc/passwd.png", "image/png", "content".toByteArray()),
                )

                Then("저장 키는 temp/ 바로 아래 한 단계로 유지된다") {
                    putRequest.captured.key() shouldStartWith "temp/"
                    putRequest.captured.key().removePrefix("temp/") shouldNotContain "/"
                }

                Then("원본 파일명은 경로를 제거한 채 기록된다") {
                    saved.captured.originalName shouldBe "passwd.png"
                }
            }
        }

        Given("temp 파일을 도메인에 첨부할 때") {
            val file = tempFile().withId(3L)

            every { repository.findByIdOrNull(3L) } returns file
            every { s3Client.copyObject(any<CopyObjectRequest>()) } returns mockk()
            every { s3Client.deleteObject(any<DeleteObjectRequest>()) } returns mockk()

            When("트랜잭션이 아직 커밋되지 않았으면") {
                TransactionSynchronizationManager.initSynchronization()
                service.attachFile(3L, "trees/1/")

                Then("temp 원본을 아직 지우지 않는다") {
                    file.storedName shouldBe "trees/1/abcd1234.png"
                    verify(exactly = 0) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
                }
            }

            When("커밋이 확정되면") {
                TransactionSynchronizationManager.initSynchronization()
                service.attachFile(3L, "trees/1/")
                val synchronizations = TransactionSynchronizationManager.getSynchronizations()

                Then("temp 원본을 지운다") {
                    synchronizations.forEach { it.afterCommit() }
                    verify(exactly = 1) {
                        s3Client.deleteObject(
                            match<DeleteObjectRequest> { it.key() == "temp/abcd1234.png" },
                        )
                    }
                }
            }
        }

        Given("detach 되어 영구 경로에 남아 있는 파일") {
            val detached = tempFile(storedName = "trees/1/abcd1234.png").withId(4L)
            every { repository.findByIdOrNull(4L) } returns detached

            When("다시 첨부를 시도하면") {
                Then("재연결을 거부한다") {
                    val exception = shouldThrow<CustomException> { service.attachFile(4L, "trees/2/") }
                    exception.message shouldContain "다시 업로드"
                    verify(exactly = 0) { s3Client.copyObject(any<CopyObjectRequest>()) }
                }
            }
        }

        Given("도메인에 첨부된 파일") {
            val attached = tempFile(storedName = "trees/1/abcd1234.png", status = FileStatus.ATTACHED).withId(5L)

            When("도메인에서 연결이 끊기면") {
                every { repository.findByIdOrNull(5L) } returns attached
                service.detachFile(5L)

                Then("상태만 TEMP 로 되돌리고 S3 객체는 남긴다") {
                    attached.status shouldBe FileStatus.TEMP
                    verify(exactly = 0) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
                }
            }

            When("여러 건이 한꺼번에 끊기면") {
                val other = tempFile(storedName = "trees/1/efgh5678.png", status = FileStatus.ATTACHED).withId(6L)
                every { repository.findAllById(listOf(5L, 6L)) } returns listOf(attached, other)
                service.detachFiles(listOf(5L, 6L))

                Then("모두 TEMP 로 되돌린다") {
                    attached.status shouldBe FileStatus.TEMP
                    other.status shouldBe FileStatus.TEMP
                    verify(exactly = 0) { s3Client.deleteObject(any<DeleteObjectRequest>()) }
                }
            }
        }
    })
