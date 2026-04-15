# Kế hoạch nâng cấp DocPilot

## 1. Mục tiêu

Mục tiêu của đợt nâng cấp này là biến DocPilot hiện tại thành một sản phẩm đi đúng hướng “GitHub Copilot for DOCX” trước đã, thay vì tiếp tục đi theo hướng “chat với HTML rồi nhờ model rewrite cả tài liệu”.

Trong giai đoạn này:

- DOCX là định dạng ưu tiên tuyệt đối.
- PDF để sau, không được phép làm lệch thiết kế gốc.
- Ứng dụng phải vận hành như một coding agent, nhưng dành cho tài liệu.
- Người dùng vẫn được sửa thủ công trên bề mặt HTML editor.
- AI edit và manual edit phải cùng đi qua một mô hình tài liệu thống nhất.
- Kết quả cuối cùng phải hướng đến luồng production-ready, nhưng chia nhỏ thành 3 phase để coding agent không bị quá tải khi triển khai.

## 2. Kết luận ngắn gọn

Kiến trúc hiện tại chưa phù hợp cho một document agent production-grade.

Các vấn đề gốc:

- Frontend đang gửi toàn bộ `document.html` trong mỗi request chat.
- Core engine đang nhét HTML vào prompt qua `CURRENT_DOCUMENT` và yêu cầu model trả lại cả tài liệu trong `DOCUMENT`.
- Model đang bị dùng như một HTML rewriter, không phải document agent.
- Turn chỉ hỏi đáp vẫn phải mang full document context, rất tốn token.
- Luồng hiện tại là one-shot generation, chưa có vòng lặp `inspect -> plan -> tool -> validate -> propose revision`.
- Frontend hiện có dual write path: AI tạo `pendingHtml`, còn user chỉnh tay thì commit trực tiếp lên `html`.
- `doc-processor` hiện mới là dịch vụ convert/query, chưa phải document engine thực thụ.
- `DocumentStructure` hiện tại chỉ là outline/statistics; ID kiểu `p_*`, `tbl_*` không đủ ổn định để làm anchor edit an toàn.

Nếu tiếp tục mở rộng theo hướng hiện tại, hệ thống sẽ gặp đúng các vấn đề sau:

- đốt token mạnh
- edit sai mục tiêu
- khó kiểm soát khi nào được sửa, khi nào chỉ được trả lời
- khó review diff một cách đáng tin cậy
- khó giữ style
- gần như không thể tiến hóa thành agent loop thật sự

## 3. Định nghĩa sản phẩm đích

DocPilot sau nâng cấp phải hoạt động như sau:

- Mở một file DOCX và tạo document session chuẩn.
- Người dùng có thể làm việc trong một workspace nhiều tài liệu, nhưng mỗi lần edit chỉ được ghi lên đúng một tài liệu đích.
- Ứng dụng có 2 mode rõ ràng: `ask` và `agent`.
- `ask` chỉ được đọc, phân tích, tìm kiếm, giải thích; tuyệt đối không được sửa tài liệu.
- `agent` được phép lập kế hoạch, gọi tool, tạo patch, tạo revision, đề xuất thay đổi và thực thi các bước cần thiết trên tài liệu đích đang được chọn.
- Người dùng vẫn có thể sửa tay trên editor surface.
- Dù là AI edit hay manual edit, tất cả đều phải hội tụ về cùng một mô hình tài liệu và cùng một revision pipeline.
- Người dùng luôn nhìn thấy diff, review, apply, reject và undo rõ ràng.

Nói ngắn gọn: đây phải là Copilot cho tài liệu, không phải chatbot bọc quanh HTML.

## 4. Hai mode bắt buộc: `ask` và `agent`

### 4.1. `ask` mode

Đây là mode an toàn tuyệt đối, tương đương “chỉ hỏi, không được sửa”.

Trong mode này, hệ thống được phép:

- đọc metadata tài liệu
- đọc cấu trúc tài liệu
- tìm section, block, table, style
- trích ngữ cảnh liên quan
- tóm tắt, phân tích, giải thích
- trả lời câu hỏi về nội dung, bố cục, style, consistency

Trong mode này, hệ thống không được phép:

- tạo patch
- apply patch
- tạo revision pending
- mutate document session
- chạy tool có side effect

Rule cứng:

- mọi mutating tool phải bị chặn ở policy layer khi request đang ở `ask` mode
- nếu model cố tạo chỉnh sửa, runtime phải từ chối và yêu cầu chuyển sang `agent` mode

### 4.2. `agent` mode

Đây là mode tác vụ đầy đủ, tương đương coding agent nhưng cho tài liệu.

Trong mode này, hệ thống được phép:

- đọc và phân tích tài liệu
- lập kế hoạch chỉnh sửa
- tìm vùng cần sửa
- rewrite nội dung
- sửa structure, style, layout trong phạm vi an toàn
- tạo patch, revision
- validate
- đề xuất diff để user review
- đọc context từ nhiều tài liệu trong workspace nếu cần tham chiếu
- nhưng chỉ được mutate đúng một tài liệu đích trong mỗi turn edit

Rule cứng:

- không được âm thầm sửa tài liệu mà không có revision
- mọi thay đổi phải đi qua patch/revision model
- mọi thao tác ghi phải có audit trail

## 5. Nguyên tắc kiến trúc

### 5.1. Document-native, không prompt-native

Tài liệu phải tồn tại như một canonical document session trong document engine. Prompt chỉ mang instruction và context tối thiểu, không mang full document representation.

### 5.2. HTML chỉ là projection

HTML vẫn hữu ích cho preview và editor surface, nhưng chỉ là projection/render artifact. HTML không được là source of truth của AI turn.

### 5.3. AI edit và manual edit phải chung một đường dữ liệu

Không được tồn tại hai luồng chỉnh sửa tách biệt. User sửa tay trên HTML editor vẫn được, nhưng editor phải map về canonical document model và tạo patch/revision như AI.

### 5.4. Agent phải có vòng lặp chuẩn

Luồng đúng là:

- nhận yêu cầu
- xác định mode và intent
- truy xuất context tối thiểu bằng tool
- lập kế hoạch
- gọi tool phù hợp
- validate
- tạo revision
- stream kết quả và trạng thái

### 5.5. Tool surface phải được layer hóa

Không expose toàn bộ primitive tool trực tiếp cho model. Phải chia thành 3 tầng:

- L1: primitive engine
- L2: composable workflows
- L3: AI-facing tools

### 5.6. Revision là first-class object

Mọi thay đổi đều phải có:

- `baseRevisionId`
- `patch`
- `validation`
- `preview`
- `apply/reject/rollback`

## 6. Kiến trúc mục tiêu

```text
Desktop App
  -> Core Engine
    -> Doc Processor MCP Server
      -> Document Session Store
      -> Revision Store
      -> DOCX import/export pipeline
```

### 6.1. Desktop App

Desktop App chịu trách nhiệm:

- quản lý chat state
- quản lý mode `ask` hoặc `agent`
- quản lý editor state
- render preview hoặc projection của document session
- hiển thị tool progress, revision preview, diff, review actions
- gửi `documentSessionId`, `chatId`, `mode`, `prompt`, `selection`, `workspaceContext`

Desktop App không được làm:

- gửi full `document.html` trong mỗi turn
- coi HTML là truth của AI flow

### 6.2. Core Engine

Core Engine là runtime của agent:

- phân loại intent
- enforce mode policy
- quyết định khi nào chỉ trả lời, khi nào được edit
- chạy vòng lặp tool-driven
- quản lý provider capability
- quản lý token budget và tool budget
- stream kết quả về frontend

Core Engine không nên trực tiếp sửa tài liệu bằng string.

### 6.3. Doc Processor MCP Server

Doc Processor phải trở thành document engine + MCP server:

- giữ canonical document sessions
- expose các tool document-native
- quản lý patch, revision, snapshot
- validate structure, style, layout
- render projection sang HTML cho editor hoặc viewer
- export lại DOCX với fidelity cao

## 7. Vì sao `doc-processor` phải là trung tâm của hệ thống

Hiện tại `doc-processor` đã có các mảnh nền tương đối tốt:

- import DOCX sang HTML
- export HTML sang DOCX với style restore
- lưu `StyleRegistry`
- extract `DocumentStructure`
- lưu original DOCX theo `docId`

Nhưng hiện tại vẫn thiếu các mảnh quyết định để trở thành document engine:

- stable anchors
- block identity bền vững
- run và range edit primitives
- patch transaction
- revision history
- dry-run
- validation
- preview projection từ canonical session

Vì vậy hướng đúng là nâng `doc-processor` thành nơi nắm source of truth của document editing, thay vì để Python backend tiếp tục thao tác ở mức string hoặc HTML.

## 8. Mô hình tài liệu chuẩn cho DOCX

Đây là phần bắt buộc phải làm rõ vì user vẫn cần sửa tay trên content HTML và AI vẫn phải giữ style tốt. Cách duy nhất làm đúng là editor surface phải map được về một component model gần với DOCX thay vì raw HTML tự do.

### 8.1. Sáu lớp bắt buộc

Document engine phải bao phủ đủ 6 lớp sau:

1. IO
- load
- save
- import
- export

2. Structure
- document
- section
- block tree

3. Content
- text
- run
- range

4. Layout
- table
- list
- positioning cơ bản

5. Style
- paragraph style
- run style
- heading style
- numbering
- table style
- style definition mới

6. Change management
- patch
- revision
- diff
- rollback

### 8.2. Component model đề xuất

Để editor và AI cùng hiểu một thứ, cần chuẩn hóa các component chính như sau:

- `Document`
- `Section`
- `Paragraph`
- `TextRun`
- `Heading`
- `List`
- `ListItem`
- `Table`
- `TableRow`
- `TableCell`
- `Image`
- `Hyperlink`
- `Field`
- `Comment`
- `Bookmark`
- `Header`
- `Footer`
- `Footnote`
- `PageBreak`
- `SectionBreak`

Mỗi component cần có:

- `id`
- `type`
- `parentId`
- `children`
- `styleRef`
- `layoutProps`
- `contentProps`
- `anchor`
- `revisionMetadata`

### 8.2.1. Style management cho AI free-style

Plan không được dừng ở mức “giữ style cũ” hoặc “clone style cũ”. Nếu muốn AI có thể thiết kế hoặc tái cấu trúc tài liệu ở mức production-ready, style system phải hỗ trợ cả việc tạo style mới một cách kiểm soát được.

Document engine cần phân biệt rõ 3 loại thao tác style:

- dùng lại style đã có
- biến thể hóa từ style đã có
- tạo style hoàn toàn mới

Primitive tối thiểu cần bổ sung ở lớp style:

- `create_style`
- `update_style_definition`
- `create_table_style`
- `create_list_style`
- `preview_style_application`
- `detect_style_conflict`

Workflow AI-facing tương ứng:

- `propose_new_style`
- `propose_style_variant`
- `apply_style_plan`

Rule triển khai:

- AI không được tự ý “vẽ style mới” rồi apply ngay lên toàn tài liệu mà không qua preview.
- Mọi style mới phải được materialize thành style definition có tên, scope, inheritance và preview.
- Style mới nên ưu tiên kế thừa từ base style gần nhất để tránh phá consistency toàn tài liệu.
- Nếu style mới ảnh hưởng heading hierarchy, table formatting hoặc numbering thì phải có bước validation riêng.

Nói cách khác, AI phải có quyền tạo style mới, nhưng theo cơ chế có schema, có preview và có validation, không phải inline formatting vô tổ chức.

### 8.3. HTML editor surface phải map như thế nào

User vẫn có thể sửa trực tiếp trên HTML, nhưng HTML đó không được là free-form blob nữa. Nó phải là DOM projection có annotation rõ ràng, ví dụ:

- `data-doc-node-id`
- `data-doc-node-type`
- `data-run-id`
- `data-style-ref`
- `data-anchor`

Khi user gõ vào editor:

- frontend không commit thẳng `innerHTML` làm source of truth
- frontend phải translate DOM delta thành patch operation
- patch operation được gửi về document engine
- document engine validate rồi cập nhật canonical session
- projection HTML mới được render lại

Đây là điểm cực quan trọng. Nếu bỏ qua bước này thì manual edit và AI edit sẽ mãi là hai hệ độc lập.

### 8.4. Stable anchor là điều kiện tiên quyết

ID kiểu `p_1`, `p_2` hiện tại là không đủ.

Cần chốt luôn rằng anchor strategy đúng không phải là chọn một trong ba hướng, mà là dùng mô hình lai.

Anchor chuẩn cho mỗi node nên gồm 3 lớp:

- `stableId`: UUID được gắn khi import vào session và tồn tại xuyên suốt vòng đời session
- `logicalPath`: đường dẫn logic trong cây tài liệu, ví dụ `section[2]/table[1]/row[3]/cell[2]/paragraph[1]`
- `structuralFingerprint`: fingerprint dựa trên type, text rút gọn, styleRef, sibling context và parent context

Quy tắc sử dụng:

- `stableId` là khóa chính để tham chiếu trong session bình thường
- `logicalPath` dùng để re-locate khi cấu trúc thay đổi nhưng node vẫn còn cùng vai trò logic
- `structuralFingerprint` dùng để rescue hoặc remap khi node bị normalize, split, merge hoặc user edit nhỏ

Không dùng riêng lẻ bất kỳ chiến lược nào:

- chỉ dùng UUID thì dễ mất bám sau normalize hoặc merge node
- chỉ dùng fingerprint thì dễ trôi khi user sửa nội dung nhỏ
- chỉ dùng logical path thì dễ gãy khi reflow hoặc move block

### 8.4.1. Anchor remap policy

Khi apply patch hoặc rebase revision, engine nên remap theo thứ tự:

1. match `stableId`
2. nếu fail, thử `logicalPath`
3. nếu fail, thử `structuralFingerprint`
4. nếu có nhiều candidate, trả conflict thay vì đoán bừa

### 8.4.2. Table anchor strategy

Anchor cho table phải chi tiết hơn block thường, vì bảng rất dễ lệch khi user thêm hoặc xóa hàng cột.

Mỗi table node nên có:

- `tableId`
- `rowId`
- `cellId`
- `cellLogicalAddress` như `R3C2`
- `tableFingerprint`

Khi bảng thay đổi:

- ưu tiên bám theo `cellId`
- nếu cell bị split hoặc merge, map sang vùng cell mới và đánh dấu operation cần validate thủ công
- nếu row hoặc column bị dịch chuyển, dùng `cellLogicalAddress` + fingerprint để remap

Nếu chưa giải xong anchor table thì không nên tự tin mở AI edit mạnh cho bảng phức tạp.

Nếu chưa giải được anchor thì chưa nên mở rộng AI editing sâu.

## 9. Tool surface cho MCP

Không nên expose toàn bộ tool primitive trực tiếp cho model. Thiết kế đúng là 3 tầng.

### 9.1. L1: Primitive engine tools

Đây là lớp có độ hạt nhỏ, chính xác và phục vụ compose.

IO:

- `load_document`
- `save_document`
- `export_docx`
- `get_document_metadata`

Structure:

- `get_document_root`
- `get_block_tree`
- `get_block_by_id`
- `get_parent_block`
- `get_child_blocks`
- `create_block`
- `delete_block`
- `move_block`
- `clone_block`

Content:

- `get_text_runs`
- `replace_text_range`
- `insert_text_at`
- `delete_text_range`
- `normalize_text_runs`

Search:

- `find_text`
- `find_regex`
- `find_by_semantic`
- `get_context_window`

Table và list:

- `get_table_by_id`
- `update_cell_content`
- `insert_row`
- `delete_row`
- `create_list`
- `change_list_type`

Style:

- `get_style`
- `create_style`
- `update_style_definition`
- `apply_style`
- `apply_inline_format`
- `create_table_style`
- `set_heading_level`
- `normalize_style`

Change management:

- `create_patch`
- `dry_run_patch`
- `apply_patch`
- `rollback_patch`
- `compute_diff`
- `create_version_snapshot`

Validation:

- `validate_document_structure`
- `validate_styles`
- `check_breaking_change`

### 9.2. L2: Composable document tools

Đây là lớp workflow đã ghép từ L1, để Core Engine gọi trong đa số trường hợp.

- `inspect_document`
- `rewrite_block`
- `rewrite_selection`
- `replace_section`
- `insert_after_block`
- `clone_section_template`
- `update_table_region`
- `standardize_heading_hierarchy`
- `restyle_selection`
- `propose_style_variant`
- `propose_new_style`
- `search_and_patch`
- `summarize_section`
- `prepare_revision_preview`

### 9.3. L3: AI-facing tools

Đây mới là bề mặt model nên thấy.

- `answer_about_document`
- `locate_relevant_context`
- `propose_document_edit`
- `apply_document_edit`
- `review_pending_revision`
- `compare_revisions`
- `propose_new_style`
- `export_current_document`

Nguyên tắc:

- model không nên thấy toàn bộ L1
- model chủ yếu làm việc với L3 và một phần L2 an toàn
- L1 dùng để engine nội bộ compose, validate và test

## 10. Luồng xử lý chuẩn cho từng turn

### 10.1. Input contract mới

Mỗi turn phải gắn với:

- `chatId`
- `documentSessionId`
- `mode`
- `baseRevisionId`
- `prompt`
- `selection`
- `workspaceContext`

Payload mẫu:

```json
{
  "chatId": "chat_123",
  "documentSessionId": "doc_123",
  "mode": "ask",
  "baseRevisionId": "rev_7",
  "prompt": "Tóm tắt phần executive summary cho tôi.",
  "selection": {
    "blockId": "blk_42",
    "textRange": null
  },
  "workspaceContext": {
    "documentIds": ["doc_123"],
    "activePane": "editor",
    "visibleBlockIds": ["blk_40", "blk_41", "blk_42"]
  }
}
```

Lưu ý:

- không còn field `document.html`
- frontend chỉ gửi reference và UI hint, không gửi full document body
- `workspaceContext.documentIds` có thể chứa nhiều tài liệu để phục vụ hỏi đáp hoặc truy xuất ngữ cảnh
- nhưng request edit phải luôn có đúng một `documentSessionId` làm tài liệu đích để mutate

### 10.2. Luồng `ask` mode

```text
receive turn
  -> enforce mode = ask
  -> classify intent
  -> inspect tài liệu bằng read-only tools
  -> lấy context tối thiểu
  -> trả lời
  -> kết thúc
```

Output của `ask` mode chỉ gồm:

- câu trả lời
- trích dẫn block hoặc section liên quan
- gợi ý hành động tiếp theo nếu user muốn chuyển sang `agent`

Không được tạo revision.

### 10.3. Luồng `agent` mode

```text
receive turn
  -> enforce mode = agent
  -> classify intent
  -> inspect document hoặc workspace
  -> lập kế hoạch
  -> gọi tool phù hợp
  -> tạo patch
  -> dry-run + validate
  -> tạo revision preview
  -> stream summary + tool progress
  -> chờ user apply hoặc reject nếu cần review
```

### 10.4. Guardrails cho runtime

- giới hạn số bước tool mỗi turn
- giới hạn số revision pending mỗi turn
- nếu confidence thấp thì hỏi lại
- nếu thay đổi quá lớn thì phải báo phạm vi ảnh hưởng
- nếu request đang ở `ask` mode thì mọi mutating tool đều bị chặn

### 10.5. Token budget và context fallback

Quản lý token không thể chỉ là một ý chung chung. Runtime phải có cơ chế chọn context và fallback rõ ràng để vừa tiết kiệm token, vừa giữ độ chính xác.

### Thứ tự lấy context khuyến nghị
1. Document metadata + outline: tiêu đề, heading, style registry, cấu trúc section.
2. Section/block summary: tóm tắt nội dung từng section hoặc block.
3. Local context window: vùng text quanh selection hoặc block mà user đang hỏi/sửa.
4. Semantic retrieval: tìm top‑k block liên quan bằng semantic search trên index.
5. Full block bodies: chỉ nạp khi thật sự cần, ví dụ edit trực tiếp hoặc validate style/layout.

### Fallback policy
- Nếu vượt ngưỡng token → giảm từ full text xuống summary.
- Nếu vẫn vượt ngưỡng → chỉ lấy top‑k block liên quan theo semantic ranking.
- Nếu request là hỏi đáp toàn cục trên tài liệu dài → dùng map‑reduce summarization (chia document thành chunk, tóm tắt từng chunk, rồi hợp nhất).
- Nếu request là edit → chỉ nạp working set của vùng sẽ mutate cộng với ngữ cảnh lân cận tối thiểu.

### Dual budget
Mỗi turn có hai ngân sách độc lập:
- tokenBudget: số token tối đa cho context.
- toolBudget: số lần gọi tool tối đa.

Khi cạn budget:
- Ask mode: trả lời với phần context đã có, kèm thông báo giới hạn.
- Agent mode: dừng ở bước plan/inspect, yêu cầu user thu hẹp phạm vi hoặc chọn section cụ thể hơn.

### Nguyên tắc bổ sung
- Progressive summarization là cơ chế mặc định: runtime tự động chọn cấp độ context (metadata → summary → local window → full block) dựa trên intent và budget.

### 10.6. Concurrency, lock và conflict handling

UX không nên cứng nhắc theo kiểu user phải chờ agent xong mới được gõ. Luồng production-ready nên cho phép manual edit tiếp tục diễn ra ngay cả khi agent đang chạy, nhưng phải có transaction model rõ ràng.

Nguyên tắc khuyến nghị:

- không dùng hard lock toàn document
- dùng optimistic concurrency theo `baseRevisionId`
- mỗi turn `agent` ghi nhận `workingSet` gồm danh sách block, run, table cell dự kiến sẽ mutate
- manual edit vẫn tạo patch bình thường trên revision mới nhất

Khi agent chuẩn bị apply patch:

- nếu `baseRevisionId` vẫn là mới nhất, apply bình thường
- nếu đã có manual edit chen vào nhưng không giao cắt `workingSet`, engine tự rebase và apply
- nếu có giao cắt cùng vùng chỉnh sửa, engine không tự merge mù mà tạo `conflict revision`

`conflict revision` cần hiển thị rõ:

- thay đổi của user
- thay đổi của agent
- vùng nào xung đột
- lựa chọn: giữ của user, giữ của agent, hoặc merge thủ công

Điểm quan trọng là: cho phép user gõ song song, nhưng không đánh đổi tính đúng đắn của revision model.

## 11. API và event contract production-ready

### 11.1. API đề xuất

Document session:

- `POST /api/document-sessions/import-docx`
- `GET /api/document-sessions/{sessionId}`
- `GET /api/document-sessions/{sessionId}/summary`
- `GET /api/document-sessions/{sessionId}/projection/html`

Agent runtime:

- `POST /api/agent/turn`
- `POST /api/agent/turn/stream`

Revision:

- `GET /api/revisions/{revisionId}`
- `POST /api/revisions/{revisionId}/apply`
- `POST /api/revisions/{revisionId}/reject`
- `POST /api/revisions/{revisionId}/rollback`

Export:

- `POST /api/document-sessions/{sessionId}/export-docx`

### 11.2. Streaming events đề xuất

- `assistant_delta`
- `tool_started`
- `tool_finished`
- `revision_proposed`
- `revision_validation`
- `notice`
- `done`

Frontend phải hiển thị được tool progress, không chỉ text stream.

## 12. Document session và revision model

Cần tách rõ 3 lớp state:

1. Document session
- tài liệu chuẩn đang mở
- canonical model hiện tại

2. Revision history
- patch hoặc revision đã tạo
- patch pending
- patch đã apply hoặc reject

3. Chat history
- hội thoại với agent
- liên kết tới revision tạo ra trong mỗi turn

### 12.1. Granularity của patch và manual edit mapping

Frontend không nên translate DOM delta theo block-level mặc định, vì như vậy chỉ sửa một chữ cũng có thể thành một diff rất lớn.

Granularity khuyến nghị:

- sửa text nhỏ: `run-level` hoặc `text-range-level`
- merge hoặc split câu trong cùng paragraph: `paragraph-run operations`
- thêm hoặc xóa paragraph: `block-level`
- thay đổi list hoặc table structure: `structure-level`
- thay đổi style: `style-op` tách riêng khỏi text-op

Rule triển khai:

- ưu tiên sinh patch nhỏ nhất có thể
- chỉ escalate lên block-level khi DOM delta không thể biểu diễn an toàn ở mức run hoặc range
- text diff và style diff phải là hai lớp khác nhau trong patch model

Patch schema tối thiểu:

```json
{
  "patchId": "patch_123",
  "sessionId": "doc_123",
  "baseRevisionId": "rev_7",
  "operations": [
    {
      "op": "replace_text_range",
      "target": {
        "blockId": "blk_42",
        "runId": "run_3",
        "start": 0,
        "end": 120
      },
      "value": "Updated text"
    }
  ],
  "summary": "Viết lại executive summary theo giọng điệu trang trọng hơn",
  "validation": {
    "structureOk": true,
    "styleOk": true,
    "warnings": []
  }
}
```

Đây là nền cho review, undo, audit, multi-turn editing và export ổn định.

## 13. Thiết kế frontend đúng cho production

### 13.1. Nguyên tắc

- Chat panel không đồng nghĩa với việc mutate document.
- Revision panel là nơi review thay đổi.
- Editor surface phải cho phép sửa tay.
- Nhưng manual edit không được commit thẳng vào raw HTML truth.

### 13.2. Cách giữ manual edit mà vẫn đúng kiến trúc

Yêu cầu là user vẫn sửa thủ công được trên content HTML. Điều này hợp lý, nhưng phải làm theo cách sau:

- editor dùng HTML projection từ canonical document session
- mỗi DOM node quan trọng map tới document node thực
- khi user sửa tay, frontend thu delta và chuyển thành patch op
- backend hoặc MCP validate patch đó
- nếu hợp lệ thì cập nhật session và trả projection mới

Nói cách khác:

- user vẫn thấy HTML editor
- nhưng hệ thống bên dưới không vận hành bằng raw HTML string nữa

### 13.2.1. Diff granularity cho manual edit

Khi user sửa tay trong editor, UI không nên coi cả block là đã thay đổi nếu thực tế chỉ sửa một ký tự.

Frontend nên có DOM delta mapper theo thứ tự ưu tiên:

1. character range delta
2. text run delta
3. inline style delta
4. paragraph structure delta
5. table hoặc list structure delta

Điều này giúp:

- diff nhỏ và chính xác hơn
- conflict detection tốt hơn
- agent rebase dễ hơn
- undo hoặc redo ít phá vỡ style hơn

### 13.3. UX đích giống coding agent

UI nên có các thành phần rõ ràng:

- mode switch: `Ask` hoặc `Agent`
- chat panel
- document canvas
- outline panel
- revision panel
- tool activity panel
- workspace documents panel

Trải nghiệm mong muốn:

- ở `Ask`, user hỏi và nhận câu trả lời, tuyệt đối không có sửa đổi ngầm
- ở `Agent`, user giao việc và thấy agent dùng tool, tạo revision, đề xuất diff
- user có thể sửa tay trực tiếp trong editor
- mọi thay đổi cuối cùng đều hiện về cùng một revision system

### 13.4. Revision panel phải hiển thị gì

Revision panel không được chỉ hiển thị text diff. Với tài liệu, style và layout diff cũng quan trọng không kém.

Revision panel nên có 3 lớp hiển thị:

1. Text diff
- thêm, xóa, sửa ở mức từ hoặc câu

2. Style diff
- thay đổi font
- size
- weight
- color
- spacing before hoặc after
- alignment
- heading level

3. Layout diff
- thay đổi list level
- table row hoặc column change
- cell merge hoặc split
- margin hoặc spacing paragraph
- page break hoặc section break

Cách hiển thị khuyến nghị:

- text diff dưới dạng inline highlight
- style diff dưới dạng property cards: `font: Calibri -> Aptos`, `spacingAfter: 6pt -> 12pt`
- table diff dưới dạng before hoặc after mini-preview theo cell
- layout diff dùng badges hoặc overlay trên document canvas để user thấy vị trí bị ảnh hưởng

Revision panel cũng nên có summary cấp cao:

- bao nhiêu text edits
- bao nhiêu style edits
- bao nhiêu layout edits
- có conflict hay không

## 14. Design patterns nên dùng

### 14.1. Hexagonal architecture

- desktop là UI adapter
- core-engine là application hoặc orchestration layer
- doc-processor MCP là domain adapter của document engine
- provider adapters tách riêng theo loại provider

### 14.2. Command pattern cho patch operations

Mỗi edit operation là một command có input schema, validation và undo semantics.

### 14.3. Unit of Work cho mỗi turn

Mỗi turn `agent` là một unit of work:

- inspect
- plan
- execute
- validate
- stage revision
- commit khi user apply

### 14.4. Strategy pattern cho provider capabilities

Provider abstraction không nên chỉ là `chat()` và `stream_chat()`. Nó cần capability map như:

- `supports_tool_calling`
- `supports_parallel_tool_calls`
- `supports_json_schema`
- `supports_reasoning_budget`

### 14.5. State machine cho agent runtime

Các state khuyến nghị:

- `idle`
- `classifying`
- `inspecting`
- `planning`
- `executing_tools`
- `validating`
- `awaiting_user_review`
- `completed`
- `failed`

## 15. Thay đổi cần làm theo package

### 15.1. `packages/core-engine`

Cần thay one-shot edit agent hiện tại bằng turn orchestrator thật sự:

- bỏ prompt nhét `CURRENT_DOCUMENT`
- bỏ contract model trả full `DOCUMENT`
- thêm mode policy `ask` hoặc `agent`
- thêm intent classifier
- thêm MCP client hoặc tool broker
- thêm revision-aware response model
- nâng provider abstraction theo capability

### 15.2. `packages/doc-processor`

Cần nâng từ convert service thành document engine + MCP server:

- document session persistence
- stable anchor generation hoặc remapping
- canonical document model
- style definition registry và style creation workflows
- patch hoặc revision services
- conflict detection và rebase services
- validation services
- HTML projection service
- DOCX export từ canonical state
- MCP transport và tool registry

### 15.3. `packages/desktop`

Cần thay app contract và editor model:

- import DOCX tạo `documentSessionId`
- turn requests gửi session reference thay vì HTML
- mode switch `Ask` hoặc `Agent`
- revision panel đọc revision metadata thay vì `documentHtml`
- revision panel hiển thị cả text diff, style diff và layout diff
- document canvas dùng projection-aware editing
- manual edit và AI edit cùng dùng revision pipeline

## 16. Kế hoạch triển khai: đúng 3 phase

Đây là cách chia phase hợp lý để coding agent triển khai không bị quá tải nhưng vẫn đi đến production-ready.

## Phase 1: Upgrade BE

Mục tiêu của phase này là sửa toàn bộ application contract và agent runtime ở backend Python, nhưng chưa đòi MCP hoàn chỉnh.

### Phạm vi

- thay `POST /api/chat` kiểu cũ bằng turn contract mới
- thêm `mode: ask | agent`
- thêm `documentSessionId`
- thêm `baseRevisionId`
- thêm policy chặn mutate trong `ask`
- thêm intent classification cơ bản
- thêm response và event model mới
- đánh dấu HTML-in-request flow là legacy

### Kết quả mong muốn

- frontend không còn phải gửi full HTML cho mọi turn
- backend phân biệt rõ turn hỏi đáp và turn chỉnh sửa
- backend sẵn sàng nói chuyện với document session thay vì raw HTML

### Exit criteria

- `ask` mode chạy end-to-end mà không tạo revision
- `agent` mode có contract chuẩn để tạo revision sau này
- không còn phụ thuộc logic chính vào prompt `CURRENT_DOCUMENT`

## Phase 2: Upgrade MCP

Mục tiêu của phase này là biến `doc-processor` thành document-native MCP engine thực thụ.

### Phạm vi

- xây canonical DOCX session model
- thêm stable anchors
- thêm component model đầy đủ
- thêm style creation primitives và style proposal workflows
- thêm patch, revision, diff, validate
- thêm conflict detection, optimistic rebase và table anchor remap
- thêm HTML projection service
- expose L1, L2, L3 tools qua MCP
- hỗ trợ read-only tools cho `ask` và mutating tools cho `agent`

### Kết quả mong muốn

- AI không còn sửa tài liệu bằng string
- manual edit có thể map về patch operations
- document engine trở thành source of truth duy nhất

### Exit criteria

- mở DOCX thành session có component tree rõ ràng
- tool inspect hoặc edit chạy được qua MCP
- revision, diff, validate hoạt động end-to-end

## Phase 3: Upgrade FE

Mục tiêu của phase này là đưa toàn bộ trải nghiệm lên mức production-ready.

### Phạm vi

- thêm mode switch `Ask` hoặc `Agent`
- cập nhật chat panel theo turn model mới
- document canvas dùng projection-aware editing
- revision panel production-ready
- revision panel hiển thị rõ text, style và layout diff
- tool activity timeline
- workspace multi-document context cho hỏi đáp và tham chiếu
- nhưng mỗi hành động edit chỉ ghi trên một tài liệu đích
- manual edit và AI edit hợp nhất về cùng revision pipeline

### Kết quả mong muốn

- trải nghiệm giống coding agent nhưng cho tài liệu
- user hỏi thì chỉ được trả lời
- user giao việc thì agent tự inspect, plan, tool, propose revision
- user sửa tay vẫn giữ được style tốt hơn vì editor đang map đúng về component model

### Exit criteria

- `Ask` và `Agent` chạy trọn vẹn trên UI
- editor sửa tay không còn dual write path
- review, apply, reject và undo đầy đủ
- flow từ import -> ask hoặc agent -> manual edit -> review -> export đạt mức production-ready cho DOCX

## 17. Success criteria cuối cùng

Sau khi hoàn thành đủ 3 phase, hệ thống phải đạt được:

- request chat không còn mang full HTML của tài liệu
- có mode `ask` và `agent` rõ ràng, enforce được bằng runtime
- AI và manual edit cùng dựa trên một canonical document model
- editor HTML vẫn tồn tại nhưng chỉ là projection-aware editor, không còn là raw truth
- mọi thay đổi đều đi qua patch, revision, diff
- doc-processor trở thành MCP document engine thật sự
- frontend mang trải nghiệm kiểu coding agent cho tài liệu
- export DOCX giữ cấu trúc và style tốt hơn kiến trúc hiện tại

## 18. Quyết định kiến trúc cuối cùng

Hướng đúng cho DocPilot là:

- DOCX-first
- `ask` mode chỉ đọc, không sửa
- `agent` mode được phép thao tác trên tài liệu và tập tài liệu
- document session là source of truth
- doc-processor là MCP document engine
- core-engine là agent orchestrator
- frontend là copilot UI có editor, review và tool activity
- HTML là projection để render và edit, không phải truth của AI turn

Nếu đi đúng hướng này, DocPilot mới có thể trở thành một “GitHub Copilot for DOCX” thật sự, thay vì chỉ là một lớp chat bọc quanh full-document HTML rewriting.