package com.landrop.ui.cloud;

import com.landrop.ui.cloud.dto.Dto;
import com.landrop.ui.cloud.mapping.CloudJson;
import com.landrop.ui.cloud.mapping.CloudMapper;
import com.landrop.ui.cloud.model.Domain;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CloudMapperTest {
    private static final String CREATED = "2026-09-13T02:00:00Z";
    private static final String UPDATED = "2026-09-13T10:15:30.123456+07:00";

    @Test
    void mapsAllPersistentModelsAndPreservesNullableFields() {
        Domain.User user = CloudMapper.user(new Dto.UserResponse(
                "usr_1", "Anh", null, null, CREATED, UPDATED));
        assertEquals("usr_1", user.id());
        assertNull(user.email());
        assertNull(user.avatarUrl());
        assertEquals(Instant.parse("2026-09-13T03:15:30.123456Z"), user.updatedAt());

        Domain.Workspace workspace = CloudMapper.workspace(new Dto.WorkspaceResponse(
                "ws_1", "LANDrop", user.id(), null, CREATED, UPDATED));
        assertEquals(user.id(), workspace.ownerId());
        assertNull(workspace.description());

        Domain.WorkspaceMember member = CloudMapper.member(new Dto.WorkspaceMemberResponse(
                "member_1", workspace.id(), user.id(), "OWNER", CREATED, UPDATED));
        assertEquals(Domain.MemberRole.OWNER, member.role());

        Domain.Channel channel = CloudMapper.channel(new Dto.ChannelResponse(
                "ch_1", workspace.id(), "general", null, CREATED, UPDATED));
        assertEquals(workspace.id(), channel.workspaceId());

        Domain.Folder folder = CloudMapper.folder(new Dto.FolderResponse(
                "folder_1", workspace.id(), null, "Shared", user.id(), CREATED, UPDATED));
        assertNull(folder.parentId());

        Domain.File file = CloudMapper.file(fileDto("9007199254740993"));
        assertEquals(new BigInteger("9007199254740993"), file.sizeBytes());
        assertNull(file.sha256());
        assertNull(file.folderId());

        Domain.Message text = CloudMapper.message(messageDto("TEXT", "Hello", null));
        assertEquals(Domain.MessageType.TEXT, text.type());
        assertNull(text.replyToId());
        assertNull(text.fileId());

        Domain.Message attachment = CloudMapper.message(messageDto("FILE", "", file.id()));
        assertEquals(Domain.MessageType.FILE, attachment.type());
        assertEquals(file.id(), attachment.fileId());
        assertEquals("", attachment.content());
    }

    @Test
    void acceptsOnlyExplicitOffsetsAndChronologicalTimestamps() {
        for (String invalid : new String[] {"2026-09-13", "2026-09-13T03:15:30", "yesterday", ""}) {
            assertThrows(IllegalArgumentException.class, () -> CloudMapper.user(
                    new Dto.UserResponse("u", "User", null, null, invalid, UPDATED)));
        }
        assertThrows(IllegalArgumentException.class, () -> CloudMapper.user(
                new Dto.UserResponse("u", "User", null, null, CREATED, "2026-09-12T00:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> CloudMapper.user(
                new Dto.UserResponse("u", "User", null, null, null, UPDATED)));
    }

    @Test
    void rejectsUnknownEnumsAndInconsistentMessageAttachments() {
        for (String invalid : new String[] {"GUEST", "owner", "", "ADMINISTRATOR"}) {
            assertThrows(IllegalArgumentException.class, () -> CloudMapper.member(
                    new Dto.WorkspaceMemberResponse("m", "w", "u", invalid, CREATED, UPDATED)));
        }
        assertThrows(IllegalArgumentException.class, () -> CloudMapper.message(messageDto("IMAGE", "x", null)));
        assertThrows(IllegalArgumentException.class, () -> CloudMapper.message(messageDto("TEXT", " ", null)));
        assertThrows(IllegalArgumentException.class, () -> CloudMapper.message(messageDto("TEXT", "x", "f")));
        assertThrows(IllegalArgumentException.class, () -> CloudMapper.message(messageDto("FILE", "", null)));
        assertThrows(IllegalArgumentException.class, () -> CloudMapper.message(messageDto("FILE", null, "f")));
        assertThrows(IllegalArgumentException.class, () -> CloudMapper.message(new Dto.MessageResponse(
                "m", "c", "u", "Reply", "TEXT", "m", null, CREATED, UPDATED)));
    }

    @Test
    void fileSizesRemainExactWithinTheDatabaseRange() {
        assertEquals(BigInteger.ZERO, CloudMapper.file(fileDto("0")).sizeBytes());
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE),
                CloudMapper.file(fileDto("9223372036854775807")).sizeBytes());
        for (String invalid : new String[] {"-1", "+1", "01", "1.0", "1e3", " 1", "",
                "9223372036854775808", "9999999999999999999999999999999999"}) {
            assertThrows(IllegalArgumentException.class, () -> CloudMapper.file(fileDto(invalid)), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> CloudMapper.file(fileDto(null)));
    }

    @Test
    void domainValidationAlsoProtectsDirectConstruction() {
        Instant createdAt = Instant.parse(CREATED);
        assertThrows(IllegalArgumentException.class,
                () -> new Domain.User(" ", "User", null, null, createdAt, createdAt));
        assertThrows(IllegalArgumentException.class,
                () -> new Domain.Folder("f", "w", "f", "Folder", "u", createdAt, createdAt));
        assertThrows(IllegalArgumentException.class,
                () -> new Domain.File("f", "w", null, "u", "a.txt", "text/plain", BigInteger.ONE,
                        "not-a-hash", createdAt, createdAt));
    }

    @Test
    void decoderDistinguishesMissingNullableFieldsFromExplicitNull() {
        String valid = userJson();
        assertNull(CloudMapper.user(CloudJson.readItem(valid, Dto.UserResponse.class).data()).email());
        assertThrows(IllegalArgumentException.class, () -> CloudJson.readItem(
                valid.replace("\"email\":null,", ""), Dto.UserResponse.class));
        assertThrows(IllegalArgumentException.class, () -> CloudJson.readItem(
                valid.replace("\"id\":\"u\"", "\"id\":null"), Dto.UserResponse.class));
        assertThrows(IllegalArgumentException.class,
                () -> CloudJson.readItem("{}", Dto.UserResponse.class));
    }

    @Test
    void decoderRejectsScalarCoercionAndMalformedDocuments() {
        for (String scalar : new String[] {"42", "true", "[]", "{}"}) {
            assertThrows(IllegalArgumentException.class, () -> CloudJson.readItem(
                    userJson().replace("\"id\":\"u\"", "\"id\":" + scalar), Dto.UserResponse.class));
        }
        assertThrows(IllegalArgumentException.class,
                () -> CloudJson.readItem(userJson() + "{}", Dto.UserResponse.class));
        assertThrows(IllegalArgumentException.class, () -> CloudJson.readItem(
                userJson().replace("\"id\":\"u\"", "\"id\":\"u\",\"id\":\"other\""), Dto.UserResponse.class));
        for (String invalid : new String[] {"", "null", "[]", "{broken}", "{\"data\":null}"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> CloudJson.readItem(invalid, Dto.UserResponse.class));
        }

        String numericSize = CloudJson.write(new Dto.ItemResponse<>(fileDto("9007199254740993")))
                .replace("\"sizeBytes\":\"9007199254740993\"", "\"sizeBytes\":9007199254740993");
        assertThrows(IllegalArgumentException.class,
                () -> CloudJson.readItem(numericSize, Dto.FileResponse.class));
    }

    @Test
    void decoderToleratesAdditiveFieldsAndPreservesWireValues() {
        String json = userJson().replace("\"id\":\"u\"", "\"id\":\"u\",\"futureField\":{\"x\":true}");
        Dto.UserResponse user = CloudJson.readItem(json, Dto.UserResponse.class).data();
        assertEquals("u", user.id());
        assertEquals(user, CloudJson.readItem(CloudJson.write(new Dto.ItemResponse<>(user)),
                Dto.UserResponse.class).data());
    }

    @Test
    void pagesAreImmutableAndKeepTheContinuationCursor() {
        var input = new ArrayList<>(List.of(new Dto.UserResponse("u", "User", null, null, CREATED, UPDATED)));
        var response = new Dto.ListResponse<>(input, new Dto.PageInfo("opaque+/=cursor"));
        input.clear();
        Domain.Page<Domain.User> page = CloudMapper.page(response, CloudMapper::user);
        assertEquals(1, page.items().size());
        assertEquals("opaque+/=cursor", page.nextCursor());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
        assertThrows(UnsupportedOperationException.class, () -> response.data().clear());

        var parsed = CloudJson.readList(CloudJson.write(response), Dto.UserResponse.class);
        assertEquals(page, CloudMapper.page(parsed, CloudMapper::user));
        var empty = CloudJson.readList("{\"data\":[],\"page\":{\"nextCursor\":null}}", Dto.UserResponse.class);
        assertTrue(empty.data().isEmpty());
        assertNull(empty.page().nextCursor());
    }

    @Test
    void decoderRequiresExplicitPaginationAndValidListEntries() {
        for (String invalid : new String[] {
                "{\"data\":[]}", "{\"data\":[],\"page\":null}", "{\"data\":[],\"page\":{}}",
                "{\"data\":[],\"page\":{\"nextCursor\":42}}",
                "{\"data\":[],\"page\":{\"nextCursor\":\"\"}}",
                "{\"data\":null,\"page\":{\"nextCursor\":null}}",
                "{\"data\":[null],\"page\":{\"nextCursor\":null}}"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> CloudJson.readList(invalid, Dto.UserResponse.class), invalid);
        }
    }

    private static Dto.FileResponse fileDto(String size) {
        return new Dto.FileResponse("file_1", "ws_1", null, "usr_1", "sample.txt", "text/plain",
                size, null, CREATED, UPDATED);
    }

    private static Dto.MessageResponse messageDto(String type, String content, String fileId) {
        return new Dto.MessageResponse("msg_1", "ch_1", "usr_1", content, type, null, fileId, CREATED, UPDATED);
    }

    private static String userJson() {
        return """
                {"data":{"id":"u","displayName":"User","email":null,"avatarUrl":null,
                "createdAt":"2026-09-13T02:00:00Z","updatedAt":"2026-09-13T03:00:00Z"}}
                """;
    }
}
