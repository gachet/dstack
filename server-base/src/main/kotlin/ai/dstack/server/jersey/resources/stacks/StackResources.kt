package ai.dstack.server.jersey.resources.stacks

import ai.dstack.server.model.*
import ai.dstack.server.model.Stack
import ai.dstack.server.services.*
import ai.dstack.server.jersey.resources.*
import ai.dstack.server.jersey.resources.attachmentAlreadyExists
import ai.dstack.server.jersey.resources.badCredentials
import ai.dstack.server.jersey.resources.commentNotFound
import ai.dstack.server.jersey.resources.frameNotFound
import ai.dstack.server.jersey.resources.malformedRequest
import ai.dstack.server.jersey.resources.ok
import ai.dstack.server.jersey.resources.payload.*
import ai.dstack.server.jersey.resources.response
import ai.dstack.server.jersey.resources.stackNotFound
import ai.dstack.server.jersey.resources.status.*
import ai.dstack.server.jersey.resources.userNotFound
import ai.dstack.server.jersey.resources.userNotVerified
import ai.dstack.server.jersey.resources.users.isMalformedEmail
import ai.dstack.server.jersey.resources.users.isMalformedUserName
import mu.KLogging
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.*
import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs.*
import javax.ws.rs.container.ResourceContext
import javax.ws.rs.core.Context
import javax.ws.rs.core.HttpHeaders
import javax.ws.rs.core.Response

@Path("/stacks")
class StackResources {
    @Context
    private lateinit var resourceContext: ResourceContext

    @Inject
    private lateinit var userService: UserService

    @Inject
    private lateinit var sessionService: SessionService

    @Inject
    private lateinit var stackService: StackService

    @Inject
    private lateinit var analyticsService: AnalyticsService

    @Inject
    private lateinit var frameService: FrameService

    @Inject
    private lateinit var attachmentService: AttachmentService

    @Inject
    private lateinit var fileService: FileService

    @Inject
    private lateinit var commentService: CommentService

    @Inject
    private lateinit var permissionService: PermissionService

    @Inject
    private lateinit var emailService: EmailService

    @Inject
    private lateinit var config: AppConfig

    @Inject
    private lateinit var cardService: CardService

    companion object : KLogging()

    @POST
    @Path("/access")
    @Produces(JSON_UTF8)
    @Consumes(JSON_UTF8)
    fun access(payload: AccessPayload?, @Context headers: HttpHeaders): Response {
        logger.debug { "payload: $payload" }
        return if (payload.isMalformed) {
            malformedRequest()
        } else {
            val u = payload?.stack?.substringBefore("/", "") ?: ""
            val user = headers.bearer?.let { userService.findByToken(it) }
            if (user == null || user.name != u || !user.verified) {
                badCredentials()
            } else {
                ok()
            }
        }
    }

    @GET
    @Path("/{user}/{stack: .+}")
    @Produces(JSON_UTF8)
    fun stack(
            @PathParam("user") u: String?, @PathParam("stack") s: String?,
            @Context headers: HttpHeaders, @Context request: HttpServletRequest
    ): Response {
        logger.debug { "user: $u, stack: $s" }
        return if (u.isNullOrBlank() || s.isNullOrBlank()) {
            malformedRequest()
        } else {
            val stack = stackService.get(u, s)
            if (stack != null) {
                val session = headers.bearer?.let { sessionService.get(it) }
                val userByToken = headers.bearer?.let { if (session == null) userService.findByToken(it) else null }
                val public = !stack.private
                val owner = (session != null && (session.userName == stack.userName))
                        || userByToken != null && userByToken.name == stack.userName
                val permitted = public
                        || (session != null &&
                        (session.userName == stack.userName
                                || permissionService.get(stack.path, session.userName) != null))
                        || (userByToken != null &&
                        (userByToken.name == stack.userName
                                || permissionService.get(stack.path, userByToken.name) != null))
                if (permitted) {
                    val head = stack.head?.let { frameService.get(stack.path, it.id) }
                    val frames = frameService.findByStackPath(stack.path)
                    session?.let { sessionService.renew(it) }
                    val status = GetStackStatus(
                            StackInfo(stack.userName,
                                    stack.name,
                                    stack.private,
                                    head?.let {
                                        val attachments = attachmentService.findByFrame(it.path)
                                        FrameInfo(
                                                it.id, it.timestampMillis,
                                                attachments.map { a ->
                                                    AttachmentInfo(
                                                            a.description,
                                                            a.legacyType,
                                                            a.application,
                                                            a.contentType,
                                                            a.params.orEmpty(),
                                                            a.settings,
                                                            a.length
                                                    )
                                                }, it.params, it.message
                                        )
                                    },
                                    stack.readme,
                                    if (owner) permissionService.findByPath(stack.path)
                                            .map {
                                                PermissionInfo(
                                                        it.userName,
                                                        it.email
                                                )
                                            }.toList() else null,
                                    commentService.findByStackPath(stack.path).map {
                                        CommentInfo(it.id, it.timestampMillis, it.userName, it.text)
                                    },
                                    frames.map {
                                        BasicFrameInfo(
                                                it.id,
                                                it.timestampMillis,
                                                it.params, it.message
                                        )
                                    }
                            )
                    )
                    analyticsService.track("Stack Resources", "stacks/:user/:stack", "Get Stack",
                            status, request.remoteAddr)
                    ok(status)
                } else {
                    badCredentials()
                }
            } else {
                stackNotFound()
            }
        }
    }

    @GET
    @Path("/{user}")
    @Produces(JSON_UTF8)
    fun stacks(@PathParam("user") u: String?, @Context headers: HttpHeaders): Response {
        logger.debug { "user: $u" }
        return if (u.isNullOrBlank()) {
            malformedRequest()
        } else {
            val user = userService.get(u)
            if (user == null) {
                userNotFound()
            } else {
                val stacks = stackService.findByUser(u)
                val session = headers.bearer?.let { sessionService.get(it) }
                val owner = session != null && (user.name == session.userName)
                val sharedStacks = if (owner) {
                    // TODO: This is not optimal solution
                    permissionService.findByIdentity(u).map { p ->
                        val (u, s) = p.path.parseStackPath()
                        stackService.get(u, s)
                    }.filterNotNull()
                } else emptySequence()
                val permittedStacks = stacks.filter { stack ->
                    val public = !stack.private
                    val permitted = public || session != null &&
                            (session.userName == stack.userName
                                    || permissionService.get(stack.path, session.userName) != null)
                    permitted
                }.plus(sharedStacks)
                        .sortedWith(compareByDescending<Stack, Long?>(nullsFirst()) { it.head?.timestampMillis })
                        .toList()
                if (session != null) {
                    sessionService.renew(session)
                }
                ok(GetStacksStatus(permittedStacks.map { stack ->
                    // TODO: Store preview in frame and not load attachments each time
                    val attachments = stack.head?.id?.let { attachmentService.findByFrame(stack.path + "/" + it) }
                    BasicStackInfo(
                            stack.userName, stack.name, stack.private,
                            stack.head?.let { h ->
                                HeadInfo(h.id, h.timestampMillis,
                                    attachments?.firstOrNull()?.let { a ->
                                        PreviewInfo(a.application, a.contentType)
                                    }) },
                            if (owner) permissionService.findByPath(stack.path)
                                    .map {
                                        PermissionInfo(
                                                it.userName,
                                                it.email
                                        )
                                    }.toList() else null
                    )
                }.toList()))
            }
        }
    }

    @POST
    @Path("/push")
    @Produces(JSON_UTF8)
    @Consumes(JSON_UTF8)
    fun push(p: PushPayload?, @Context headers: HttpHeaders): Response {
        val payload = p?.migrateAttachmentType()
        logger.debug {
            "payload: ${payload?.copy(attachments = payload.attachments?.map { it.copy(data = if (it.data != null) "(hidden)" else null) }
                    ?.toList())}"
        }
        return if (payload.isMalformed) {
            malformedRequest()
        } else {
            var frame = frameService.get(payload!!.stack!!, payload.id!!)
            val (u, s) = payload.stack!!.parseStackPath()
            val user = headers.bearer?.let { t ->
                userService.findByToken(t) ?: sessionService.get(t)?.let { userService.get(it.userName) }
            }
            var stack = stackService.get(u, s)
            if (user == null || user.name != u || !user.verified) {
                badCredentials()
            } else {
                if (stack == null) {
                    val isPrivate = user.settings.general.defaultAccessLevel == AccessLevel.Private
                    stack = Stack(user.name, s, isPrivate, null, null)
                    stackService.create(stack)
                }
                if (frame == null) {
                    frame = Frame(
                            stack.path, payload.id, payload.timestamp!!,
                            if (payload.index == null && payload.attachments?.isNotEmpty() == true)
                                payload.attachments.size
                            else
                                payload.size,
                            payload.params.orEmpty().let { it.mapValues { it.value }.toMap() },
                            payload.message?.let { if (it.isNotEmpty()) it else null }
                    )
                    frameService.create(frame)
                } else if (payload.size != null) {
                    frame = frame.copy(size = payload.size)
                    frameService.update(frame)
                }
                var attachmentUploadInfos: MutableList<AttachmentUploadInfo>? = null
                if (payload.attachments != null) {
                    payload.attachments.forEachIndexed { i, a ->
                        val index = payload.index ?: i
                        try {
                            val type = a.type ?: payload.type
                            val application = a.application ?: payload.application
                            val contentType = a.contentType ?: payload.contentType
                            val file = frame.path + "/" + index
                            val data = a.data?.let { Base64.getDecoder().decode(a.data) }
                            val length = data?.count()?.toLong() ?: a.length!!
                            if (data != null) {
                                fileService.save(file, data)
                            } else {
                                val uploadUrl = fileService.upload(file, user)
                                if (attachmentUploadInfos == null) {
                                    attachmentUploadInfos = mutableListOf()
                                }
                                attachmentUploadInfos!!.add(
                                        AttachmentUploadInfo(
                                                index,
                                                uploadUrl.toString()
                                        )
                                )
                            }
                            attachmentService.create(
                                    Attachment(
                                            framePath = frame.path,
                                            filePath = file,
                                            description = a.description,
                                            legacyType = type!!,
                                            application = application,
                                            contentType = contentType!!,
                                            length = length,
                                            index = index,
                                            params = a.params.orEmpty().mapValues { it.value }.toMap(),
                                            settings = a.settings.orEmpty().mapValues { it.value }.toMap(),
                                            createdDate = LocalDate.now(ZoneOffset.UTC)
                                    )
                            )

                        } catch (e: EntityAlreadyExists) {
                            return attachmentAlreadyExists()
                        }
                    }
                }
                // TODO: Update stack's head information asynchronously / eventually
                if (frame.size != null) {
                    stackService.update(
                            stack.copy(
                                    head = Head(frame.id, frame.timestampMillis)
                            )
                    )
                }
                ok(
                        PushStatus(
                                "${config.address}/${user.name}/${stack.name}",
                                attachmentUploadInfos
                        )
                )
            }
        }
    }

    @POST
    @Path("/create")
    @Consumes(JSON_UTF8)
    @Produces(JSON_UTF8)
    fun register(payload: CreateStackPayload?, @Context headers: HttpHeaders): Response {
        logger.debug { "payload: $payload" }
        return if (payload.isMalformed) {
            malformedRequest()
        } else {
            val user = userService.get(payload!!.user!!)
            if (user == null) {
                userNotFound()
            } else if (!user.verified) {
                userNotVerified()
            } else {
                val session = headers.bearer?.let { sessionService.get(it) }
                if (session == null || !session.valid) {
                    badCredentials()
                } else {
                    val isPrivate =
                            !payload.private!! || user.settings.general.defaultAccessLevel == AccessLevel.Private
                    val stack = Stack(user.name, payload.name!!, isPrivate, null, payload.readme)
                    try {
                        sessionService.renew(session)
                        stackService.create(stack)
                        ok()
                    } catch (e: EntityAlreadyExists) {
                        response(
                                Response.Status.BAD_REQUEST,
                                OpStatus("stack already exists")
                        )
                    }
                }
            }
        }
    }

    @POST
    @Path("delete")
    @Consumes(JSON_UTF8)
    @Produces(JSON_UTF8)
    fun deleteStack(payload: DeleteStackPayload?, @Context headers: HttpHeaders): Response {
        logger.debug { "payload: $payload" }
        return if (payload.isMalformed) {
            malformedRequest()
        } else {
            val user = userService.get(payload!!.user!!)
            if (user == null) {
                userNotFound()
            } else if (!user.verified) {
                userNotVerified()
            } else {
                val session = headers.bearer?.let { sessionService.get(it) }
                if (session == null || !session.valid) {
                    badCredentials()
                } else {
                    val stack = stackService.get(user.name, payload.name!!)
                    if (stack == null) {
                        stackNotFound()
                    } else {
                        sessionService.renew(session)
                        stackService.delete(stack)
                        frameService.deleteByStackPath(stack.path)
                        attachmentService.deleteByStackPath(stack.path)
                        permissionService.deleteByPath(stack.path)
                        commentService.deleteByStackPath(stack.path)
                        cardService.deleteByStackPath(stack.path)
                        fileService.delete(stack.path)
                        ok()
                    }
                }
            }
        }
    }

    @Deprecated("Gonna be removed in October")
    @POST
    @Path("comments/create")
    @Consumes(JSON_UTF8)
    @Produces(JSON_UTF8)
    fun createComment(payload: CreateCommentPayload?, @Context headers: HttpHeaders): Response {
        logger.debug { "payload: $payload" }
        return if (payload.isMalformed) {
            malformedRequest()
        } else {
            val session = headers.bearer?.let { sessionService.get(it) }
            if (session == null || !session.valid) {
                badCredentials()
            } else {
                val (u, s) = payload!!.stack!!.parseStackPath()
                val user = userService.get(u)
                val stack = stackService.get(u, s)
                when {
                    user == null -> userNotFound()
                    !user.verified -> userNotVerified()
                    stack == null -> stackNotFound()
                    else -> {
                        sessionService.renew(session)
                        val comment = Comment(
                                UUID.randomUUID().toString(), stack.path, session.userName,
                                Instant.now().epochSecond, payload.text!!
                        )
                        commentService.create(comment)
                        if (user.settings.notifications.comments && stack.userName != session.userName
                                && emailService !is NonAvailable
                        ) {
                            emailService.sendCommentEmail(user, comment)
                        }
                        ok(
                                CreateCommentStatus(
                                        comment.id,
                                        comment.timestampMillis
                                )
                        )
                    }
                }
            }
        }
    }

    @Deprecated("Gonna be removed in October")
    @POST
    @Path("comments/delete")
    @Consumes(JSON_UTF8)
    @Produces(JSON_UTF8)
    fun deleteComment(payload: DeleteCommentPayload?, @Context headers: HttpHeaders): Response {
        logger.debug { "payload: $payload" }
        return if (payload.isMalformed) {
            malformedRequest()
        } else {
            val session = headers.bearer?.let { sessionService.get(it) }
            val user = session?.let { userService.get(it.userName) }
            val comment = commentService.get(payload!!.id!!)
            if (session == null || !session.valid || user == null) {
                badCredentials()
            } else if (!user.verified) {
                userNotVerified()
            } else if (comment == null) {
                commentNotFound()
            } else {
                sessionService.renew(session)
                commentService.delete(comment)
                ok()
            }
        }
    }

    @Deprecated("Gonna be removed in October")
    @POST
    @Path("comments/edit")
    @Consumes(JSON_UTF8)
    @Produces(JSON_UTF8)
    fun deleteComment(payload: EditCommentPayload?, @Context headers: HttpHeaders): Response {
        logger.debug { "payload: $payload" }
        return if (payload.isMalformed) {
            malformedRequest()
        } else {
            val session = headers.bearer?.let { sessionService.get(it) }
            val user = session?.let { userService.get(it.userName) }
            val comment = commentService.get(payload!!.id!!)
            if (session == null || !session.valid || user == null) {
                badCredentials()
            } else if (!user.verified) {
                userNotVerified()
            } else if (comment == null) {
                commentNotFound()
            } else {
                sessionService.renew(session)
                commentService.update(comment.copy(text = payload.text!!))
                ok()
            }
        }
    }

    @POST
    @Path("update")
    @Consumes(JSON_UTF8)
    @Produces(JSON_UTF8)
    fun updateStack(payload: UpdateStackPayload?, @Context headers: HttpHeaders): Response {
        logger.debug { "payload: $payload" }
        return if (payload.isMalformed) {
            malformedRequest()
        } else {
            val session = headers.bearer?.let { sessionService.get(it) }
            val user = session?.let { userService.get(it.userName) }
            if (session == null || !session.valid || user == null) {
                badCredentials()
            } else if (!user.verified) {
                userNotVerified()
            } else {
                val (u, s) = payload!!.stack!!.parseStackPath()
                val stack = stackService.get(u, s)
                when {
                    stack == null -> stackNotFound()
                    stack.userName != user.name -> badCredentials()
                    else -> {
                        sessionService.renew(session)
                        if (payload.head != null) {
                            val head = frameService.get(stack.path, payload.head)
                            if (head == null) {
                                frameNotFound()
                            } else {
                                stackService.update(
                                        stack.copy(
                                                private = payload.private ?: stack.private,
                                                head = Head(head.id, head.timestampMillis),
                                                readme = payload.readme
                                                        ?: (if (payload.readme?.isEmpty() == true) null else payload.readme)
                                        )
                                )
                                ok()
                            }
                        } else {
                            if (payload.private != null || payload.readme != null) {
                                stackService.update(stack.copy(private = payload.private ?: stack.private,
                                        readme = payload.readme
                                                ?: (if (payload.readme?.isEmpty() == true) null else payload.readme)))
                            }
                            ok()
                        }
                        ok()
                    }
                }
            }
        }
    }
}

fun PushPayload.migrateAttachmentType(): PushPayload {
    val values = AttachmentTypeMigration.migrate(this.type, this.application, this.contentType)
    return this.copy(type = values.legacyType, application = values.application, contentType = values.contentType,
            attachments = this.attachments?.map { it.migrateAttachmentType() }
    )
}

fun PushPayloadAttachment.migrateAttachmentType(): PushPayloadAttachment {
    val values = AttachmentTypeMigration.migrate(this.type, this.application, this.contentType)
    return this.copy(type = values.legacyType, application = values.application, contentType = values.contentType)
}

fun String.parseStackPath(): Pair<String, String> {
    val u = substringBefore("/", "")
    val s = substringAfter("/", "")
    return Pair(u, s)
}

private val AccessPayload?.isMalformed get() = this?.stack.isNullOrBlank()

private val DeleteStackPayload?.isMalformed
    get() = this?.user.isNullOrBlank() || this?.name.isNullOrBlank()

private val UpdateStackPayload?.isMalformed
    get() = this == null
            || stack.isMalformedStackPath
            || (private == null && head == null && readme == null)

private val DeleteCommentPayload?.isMalformed
    get() = this?.id.isNullOrBlank()

private val EditCommentPayload?.isMalformed
    get() = this?.id.isNullOrBlank() || this?.text.isNullOrBlank()

const val STACK_NAME_PATTERN = "[a-zA-Z0-9-_/]{3,255}\$"

val CreateCommentPayload?.isMalformed
    get() = this == null
            || this.stack.isMalformedStackPath
            || this.text.isNullOrBlank()

val UpdatePermissionPayload?.isMalformed
    get() = this == null
            || (this.path != null && this.path.isMalformedStackPath)
            || (this.user != null && this.user.isMalformedUserName)
            || (this.email != null && this.email.isMalformedEmail)
            || (this.user == null && this.email == null)
            || (this.user != null && this.email != null)

private val CreateStackPayload?.isMalformed
    get() = this == null
            || this.user.isMalformedUserName
            || this.name.isMalformedStackName

val String?.isMalformedStackName
    get() = this.isNullOrBlank()
            || !this.matches("^$STACK_NAME_PATTERN\$".toRegex())
            || this.contains("-{2,}".toRegex())
            || this.contains("/{2,}".toRegex())
            || this.startsWith("-")
            || this.startsWith("/")
            || this.endsWith("-")
            || this.endsWith("/")

private val String?.isMalformedStackPath
    get() = this.isNullOrBlank() || !this.matches("^[^/]+/$STACK_NAME_PATTERN\$".toRegex())

// TODO: Add validation for attachments
// TODO: Add size limitation for all string values
val PushPayload?.isMalformed: Boolean
    get() {
        val missingRequiredFields =
                this?.stack.isNullOrBlank() || this?.id.isNullOrBlank() || this?.timestamp == null
        val missingSize = this?.size == null
        val missingAttachments = (this?.attachments?.size ?: 0) == 0
        val missingType = (this?.type == null || this.contentType == null)
                && this?.attachments?.any {
            it.type == null || it.contentType == null
        } == true
        // TODO: Do additional validation for parameters
        val containsMalformedAttachments = this?.attachments?.any {
            (it.data == null && it.length == null) ||
                    it.params?.values?.any { p -> !(p is String || p is Number || p is Boolean
                            || (p is Map<*, *> && p["type"] != null)) } == true
        } == true
        val missingIndex = this?.index == null
        val missingSingleAttachment = this?.attachments?.size != 1
        val malformedStackName = this?.stack?.substringAfter("/").isMalformedStackName

        return missingRequiredFields
                || containsMalformedAttachments // any of attachment is malformed is missing or any of params is anything but String, number of boolean
                || (missingAttachments && missingSize) // doesn't have `attachments` (exception: when it has `size`)
                || (missingType && missingSize) // doesn't have `type` (exception: when it has `size`)
                || (missingSingleAttachment && !missingIndex) // doesn't have a single attachment if it has `index`
                || malformedStackName // stack name is malformed
    }