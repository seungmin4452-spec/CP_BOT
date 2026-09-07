(function () {
    const csrfToken = document.querySelector('meta[name="_csrf"]').content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;

    function showAlert(alertBox, kind, node) {
        alertBox.hidden = false;
        alertBox.className = `alert alert-${kind}`;
        alertBox.replaceChildren(node);
    }

    function showText(alertBox, kind, text) {
        showAlert(alertBox, kind, document.createTextNode(text));
    }

    // DOM을 건드리지 않는 저수준 업로드 - 여러 파일을 순차 업로드할 때 중간 실패가 이전 결과를 덮어쓰지 않도록 분리했다.
    async function postFile(url, formData) {
        try {
            const res = await fetch(url, {
                method: 'POST',
                headers: { [csrfHeader]: csrfToken },
                body: formData
            });

            if (res.status === 401) {
                window.location.href = '/login';
                return { ok: false, redirecting: true };
            }

            const data = await res.json().catch(() => null);

            if (!res.ok) {
                const message = (data && data.message) ? data.message : `요청에 실패했습니다. (HTTP ${res.status})`;
                return { ok: false, message };
            }

            return { ok: true, data };
        } catch (err) {
            return { ok: false, message: '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.' };
        }
    }

    function checkedRoles(form) {
        return Array.from(form.querySelectorAll('input[name="allowedRoles"]:checked')).map((el) => el.value);
    }

    // 단일 파일 업로드
    const form = document.getElementById('upload-form');
    const uploadBtn = document.getElementById('upload-btn');
    const alertBox = document.getElementById('result-alert');

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        if (checkedRoles(form).length === 0) {
            showText(alertBox, 'error', '열람 권한을 하나 이상 선택하세요.');
            return;
        }

        const formData = new FormData(form);
        uploadBtn.disabled = true;
        uploadBtn.textContent = '업로드 중...';
        alertBox.hidden = true;

        const result = await postFile('/api/documents', formData);
        if (result.ok) {
            const data = result.data;
            showText(alertBox, 'success',
                `"${data.documentTitle}" 업로드 완료 - ${data.pageCount}개 단위, ${data.chunkCount}개 청크로 색인되었습니다.`);
            form.reset();
            document.getElementById('category').value = '일반';
        } else if (!result.redirecting) {
            showText(alertBox, 'error', result.message);
        }

        uploadBtn.disabled = false;
        uploadBtn.textContent = '업로드';
    });

    // zip 일괄 업로드 (여러 zip을 한 번에 선택하면 순차적으로 하나씩 업로드하고 결과를 합쳐서 보여준다)
    const zipForm = document.getElementById('zip-upload-form');
    const zipFileInput = document.getElementById('zip-file');
    const zipUploadBtn = document.getElementById('zip-upload-btn');
    const zipAlertBox = document.getElementById('zip-result-alert');

    zipForm.addEventListener('submit', async (e) => {
        e.preventDefault();

        const files = Array.from(zipFileInput.files);
        if (files.length === 0) {
            return;
        }
        const roles = checkedRoles(zipForm);
        if (roles.length === 0) {
            showText(zipAlertBox, 'error', '열람 권한을 하나 이상 선택하세요.');
            return;
        }

        zipUploadBtn.disabled = true;
        zipAlertBox.hidden = true;

        const succeeded = [];
        const skipped = [];
        const failedZips = [];
        const multi = files.length > 1;

        for (let i = 0; i < files.length; i++) {
            const file = files[i];
            zipUploadBtn.textContent = multi ? `업로드 중... (${i + 1}/${files.length})` : '업로드 중...';

            const formData = new FormData();
            formData.append('file', file);
            roles.forEach((role) => formData.append('allowedRoles', role));

            const result = await postFile('/api/documents/batch', formData);
            if (result.redirecting) {
                return;
            }
            if (result.ok) {
                const prefix = multi ? `${file.name} » ` : '';
                result.data.succeeded.forEach((s) => succeeded.push({ ...s, entryName: prefix + s.entryName }));
                result.data.skipped.forEach((s) => skipped.push({ ...s, entryName: prefix + s.entryName }));
            } else {
                failedZips.push(`${file.name} - ${result.message}`);
            }
        }

        renderZipResult(succeeded, skipped, failedZips);
        if (succeeded.length > 0) {
            zipForm.reset();
        }

        zipUploadBtn.disabled = false;
        zipUploadBtn.textContent = 'zip 업로드';
    });

    function renderZipResult(succeeded, skipped, failedZips) {
        const summary = document.createElement('div');
        summary.textContent = failedZips.length > 0
            ? `${succeeded.length}개 파일 색인 완료, ${skipped.length}개 건너뜀, ${failedZips.length}개 zip 자체 실패`
            : `${succeeded.length}개 파일 색인 완료, ${skipped.length}개 건너뜀`;

        const container = document.createDocumentFragment();
        container.appendChild(summary);

        function appendList(items, formatter) {
            const list = document.createElement('ul');
            list.className = 'skipped-list';
            items.forEach((item) => {
                const li = document.createElement('li');
                li.textContent = formatter(item);
                list.appendChild(li);
            });
            container.appendChild(list);
        }

        if (failedZips.length > 0) {
            appendList(failedZips, (item) => item);
        }
        if (skipped.length > 0) {
            appendList(skipped, (s) => `${s.entryName} - ${s.reason}`);
        }

        const kind = (skipped.length > 0 || failedZips.length > 0)
            ? (succeeded.length > 0 ? 'info' : 'error')
            : 'success';
        showAlert(zipAlertBox, kind, container);
    }
})();
