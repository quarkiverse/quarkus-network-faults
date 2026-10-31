import { LitElement, html, css } from 'lit';
import { JsonRpc } from 'jsonrpc';
import { notifier } from 'notifier';
import { columnBodyRenderer, gridRowDetailsRenderer } from '@vaadin/grid/lit.js';
import { comboBoxRenderer } from '@vaadin/combo-box/lit.js';
import '@vaadin/grid';
import '@vaadin/button';
import '@vaadin/combo-box';
import '@vaadin/text-field';
import '@vaadin/text-area';
import '@vaadin/details';

/**
 * Network faults: one row per proxied outbound connection, with fault toggles, an event log and the HTTP requests
 * of a target.
 */
export class QwcNetworkFaults extends LitElement {
    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host { display: flex; flex-direction: column; gap: 10px; padding: 10px; }
        .toolbar { display: flex; gap: 10px; align-items: center; }
        .hint { color: var(--lumo-secondary-text-color); }
        .active { color: var(--lumo-error-text-color); font-weight: bold; }
        .none { color: var(--lumo-success-text-color); }
        .row { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
        .actions { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 6px; padding: 0 0 6px 0; }
        .fault-field { width: 13em; }
        .scope-field { width: 10em; }
        .ok { color: var(--lumo-success-text-color); }
        .error { color: var(--lumo-error-text-color); }
        pre { font-size: var(--lumo-font-size-xs); margin: 0; }
        pre.report { white-space: pre-wrap; }
        code { font-size: var(--lumo-font-size-s); }
        vaadin-grid-cell-content { white-space: normal; }
    `;

    static properties = {
        _status: { state: true },
        _faults: { state: true },
        _forms: { state: true },
        _events: { state: true },
        _eventsTarget: { state: true },
        _requests: { state: true },
        _requestsTarget: { state: true },
        _openedRequest: { state: true },
        _report: { state: true }
    };

    constructor() {
        super();
        this._status = null;
        this._faults = [];
        this._forms = {};
        this._events = [];
        this._eventsTarget = null;
        this._requests = { requests: [], reason: '' };
        this._requestsTarget = null;
        this._openedRequest = null;
        this._report = null;
        // the faults a target that is not plaintext HTTP can take, filtered once
        this._opaqueFaults = [];
    }

    connectedCallback() {
        super.connectedCallback();
        this.jsonRpc.getFaults().then(r => {
            this._faults = r.result;
            this._opaqueFaults = this._faults.filter(f => !f.readsHttp);
        });
        this._refresh();
        this._timer = setInterval(() => this._refresh(), 2000);
    }

    disconnectedCallback() {
        clearInterval(this._timer);
        super.disconnectedCallback();
    }

    _refresh() {
        this.jsonRpc.getStatus().then(r => { this._status = r.result; });
        if (this._eventsTarget) {
            // newest first, formatted once per fetch rather than on every render
            this.jsonRpc.getEvents({ target: this._eventsTarget }).then(r => {
                this._events = r.result.reverse().map(e => `${e.time}  ${e.type}${e.connectionId >= 0 ? ' #' + e.connectionId : ''}  ${e.detail}`);
            });
        }
        if (this._requestsTarget) {
            this.jsonRpc.getRequests({ target: this._requestsTarget }).then(r => {
                // keyed once per fetch, so the grid keeps its row state across refreshes
                const requests = r.result.requests.map((q, i) => ({ ...q, key: `${i} ${q.connectionId} ${q.time}` }));
                this._requests = { requests: requests, reason: r.result.reason };
            });
        }
    }

    render() {
        if (!this._status) {
            return html`<span class="hint">Loading…</span>`;
        }
        if (!this._status.enabled) {
            return html`<span class="hint">${this._status.hint}</span>`;
        }
        return html`
            <div class="toolbar">
                <vaadin-button theme="small" @click=${() => this._call('resetAll', {})}>End all faults</vaadin-button>
                <vaadin-button theme="small" @click=${() => this._export()}>Export report</vaadin-button>
                <span class="hint">${this._status.targets.length} target(s), ${this._status.faultsActive} fault(s) active; refreshed every 2s</span>
            </div>
            <vaadin-grid .items=${this._status.targets} all-rows-visible item-id-path="name" .detailsOpenedItems=${this._status.targets}
                ${gridRowDetailsRenderer(t => this._actions(t), [this._forms, this._faults])}>
                <vaadin-grid-column header="Target" auto-width ${columnBodyRenderer(t => html`<code>${t.name}</code><br/><span class="hint">${t.sourceProperty}</span>`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Proxy → upstream" auto-width ${columnBodyRenderer(t => html`127.0.0.1:${t.proxyPort} → ${t.upstream}${t.listening ? '' : ' (not listening)'}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Fault" flex-grow="2" ${columnBodyRenderer(t => this._faultCell(t), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Connections" ${columnBodyRenderer(t => html`${t.connections.length} live, ${t.connectionsSeen} seen, ${t.connectionsReset} reset`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Bytes ↑ / ↓" ${columnBodyRenderer(t => html`${t.bytesUpstream} / ${t.bytesDownstream}`, [])}></vaadin-grid-column>
            </vaadin-grid>
            ${this._eventsTarget ? html`
                <vaadin-details summary="Events of ${this._eventsTarget}" opened>
                    <span class="hint">${this._events.length} event(s), newest first, refreshed every 2s</span>
                    <pre>${this._events.join('\n')}</pre>
                </vaadin-details>` : ''}
            ${this._requestsTarget ? this._requestsView() : ''}
            ${this._report ? html`
                <vaadin-details summary="Report generated at ${new Date(this._report.generatedAt).toLocaleTimeString()}" opened>
                    <div class="row">
                        <vaadin-button theme="small" @click=${() => this._copyReport()}>Copy</vaadin-button>
                        <vaadin-button theme="small" @click=${() => this._downloadReport()}>Download</vaadin-button>
                    </div>
                    <pre class="report">${this._report.markdown}</pre>
                </vaadin-details>` : ''}
        `;
    }

    _actions(t) {
        const form = this._form(t);
        const reason = t.notHttpReason ?? '';
        const blocked = !t.plaintextHttp && this._readsHttp(form.fault);
        const faults = t.plaintextHttp ? this._faults : this._opaqueFaults;
        return html`
            <div class="actions">
                <div class="row">
                    <vaadin-combo-box class="fault-field" theme="small" allow-custom-value title=${reason}
                        .items=${faults} item-label-path="spec" item-value-path="spec" .value=${form.fault}
                        ${comboBoxRenderer(f => html`<div title=${f.meaning}>${f.spec}<br/><span style="color: var(--lumo-secondary-text-color); font-size: var(--lumo-font-size-xs)">${f.meaning}</span></div>`, [])}
                        @value-changed=${e => this._edit(t, 'fault', e.detail.value)}></vaadin-combo-box>
                    <span title=${reason}>
                        <vaadin-text-field class="scope-field" theme="small" placeholder="POST /path" clear-button-visible
                            title="Fault only these requests; empty for every connection" ?disabled=${!t.plaintextHttp}
                            .value=${form.requests} @value-changed=${e => this._edit(t, 'requests', e.detail.value)}></vaadin-text-field>
                    </span>
                    ${this._respondsWith(form.fault) ? html`
                        <vaadin-text-field theme="small" placeholder="Body" .value=${form.body}
                            @value-changed=${e => this._edit(t, 'body', e.detail.value)}></vaadin-text-field>
                        <vaadin-text-field theme="small" placeholder="application/json" title="Content type" .value=${form.contentType}
                            @value-changed=${e => this._edit(t, 'contentType', e.detail.value)}></vaadin-text-field>
                        <vaadin-text-area theme="small" placeholder="Retry-After: 30" title="Response headers, one Name: value per line"
                            .value=${form.headers} @value-changed=${e => this._edit(t, 'headers', e.detail.value)}></vaadin-text-area>` : ''}
                    <span title=${blocked ? reason : ''}>
                        <vaadin-button theme="small primary" ?disabled=${!form.fault || blocked} @click=${() => this._apply(t)}>Apply</vaadin-button>
                    </span>
                </div>
                <div class="row">
                    <vaadin-button theme="small" ?disabled=${t.fault === 'none'} @click=${() => this._call('reset', { target: t.name })}>End</vaadin-button>
                    <vaadin-button theme="small" @click=${() => this._call('dropConnections', { target: t.name })}>Drop connections</vaadin-button>
                    <vaadin-button theme="small tertiary" @click=${() => { this._eventsTarget = t.name; this._refresh(); }}>Events</vaadin-button>
                    <span title=${reason}>
                        <vaadin-button theme="small tertiary" ?disabled=${!t.plaintextHttp} @click=${() => this._showRequests(t.name)}>Requests</vaadin-button>
                    </span>
                </div>
            </div>`;
    }

    _requestsView() {
        return html`
            <vaadin-details summary="Requests of ${this._requestsTarget}" opened>
                ${this._requests.reason ? html`<span class="hint">${this._requests.reason}</span>` : ''}
                <vaadin-grid .items=${this._requests.requests}
                    item-id-path="key" all-rows-visible theme="compact row-stripes"
                    .detailsOpenedItems=${this._requests.requests.filter(r => r.key === this._openedRequest)}
                    @click=${e => this._toggleRequest(e.currentTarget.getEventContext(e).item)}
                    ${gridRowDetailsRenderer(r => html`<pre>${this._requestDetails(r)}</pre>`, [])}>
                    <vaadin-grid-column header="Time" ${columnBodyRenderer(r => html`${new Date(r.time).toLocaleTimeString()}`, [])}></vaadin-grid-column>
                    <vaadin-grid-column header="Method" ${columnBodyRenderer(r => html`<code>${r.method}</code>`, [])}></vaadin-grid-column>
                    <vaadin-grid-column header="Target" flex-grow="3" ${columnBodyRenderer(r => html`<code>${r.target}</code>`, [])}></vaadin-grid-column>
                    <vaadin-grid-column header="Status" ${columnBodyRenderer(r => this._responseStatus(r), [])}></vaadin-grid-column>
                    <vaadin-grid-column header="Response time" ${columnBodyRenderer(r => html`${r.responseTimeMillis === null ? '' : r.responseTimeMillis + ' ms'}`, [])}></vaadin-grid-column>
                </vaadin-grid>
            </vaadin-details>`;
    }

    _toggleRequest(r) {
        if (r) {
            this._openedRequest = this._openedRequest === r.key ? null : r.key;
        }
    }

    _requestDetails(r) {
        const lines = [];
        for (const [name, values] of Object.entries(r.headers)) {
            for (const value of values) {
                lines.push(`${name}: ${value}`);
            }
        }
        lines.push('', `body SHA-256: ${r.bodyFingerprint ?? 'no body'}`);
        return lines.join('\n');
    }

    _responseStatus(r) {
        if (r.responseStatus === null) {
            return html`<span class="hint" title="Never answered, or still in flight; a respondsWith answer carries its status">none</span>`;
        }
        return html`<span class="${r.responseStatus < 400 ? 'ok' : 'error'}">${r.responseStatus}</span>`;
    }

    _showRequests(target) {
        this._requestsTarget = target;
        this._openedRequest = null;
        this._refresh();
    }

    // the active fault, or what the last one did: the guard's verdict is the dev-mode form of the test guard
    _faultCell(t) {
        if (t.fault !== 'none') {
            return html`<span class="active">${t.fault}</span>`;
        }
        const last = t.lastFault;
        return html`<span class="none">none</span>${last ? html`<br/><span class="hint">last: <code>${last.fault}</code> ${this._verdict(last)}</span>` : ''}`;
    }

    _verdict(last) {
        switch (last.verdict) {
            case 'not-judged':
                return html`<span title="The guard could not watch this fault: refused and dropped connections never reach the proxy, and the waiters of some pools cannot be observed; what the application did is the evidence">not judged</span>`;
            case 'saw-traffic':
                return html`<span class="ok">saw ${last.connectionsSeen} connection(s), ${last.httpRequestsSeen} request(s)</span>`;
            default:
                return html`<span class="error" title="No traffic went through this fault, so it cannot have affected the application">no traffic</span>`;
        }
    }

    _export() {
        this.jsonRpc.getReport().then(r => { this._report = r.result; })
            .catch(e => notifier.showErrorMessage(String(e)));
    }

    _copyReport() {
        navigator.clipboard.writeText(this._report.markdown)
            .then(() => notifier.showInfoMessage('Report copied'))
            .catch(e => notifier.showErrorMessage(String(e)));
    }

    _downloadReport() {
        const link = document.createElement('a');
        link.href = URL.createObjectURL(new Blob([this._report.markdown], { type: 'text/markdown' }));
        link.download = 'network-faults-report.md';
        link.click();
        setTimeout(() => URL.revokeObjectURL(link.href), 0);
    }

    _form(t) {
        return this._forms[t.name] || { fault: this._faults[0]?.spec, requests: '', body: '', contentType: '', headers: '' };
    }

    _edit(t, field, value) {
        this._forms = { ...this._forms, [t.name]: { ...this._form(t), [field]: value } };
    }

    _apply(t) {
        const form = this._form(t);
        const respondsWith = this._respondsWith(form.fault);
        this._call('apply', {
            target: t.name,
            fault: form.fault,
            requests: form.requests,
            body: respondsWith ? form.body : '',
            contentType: respondsWith ? form.contentType : '',
            headers: respondsWith ? form.headers : ''
        });
    }

    // the kind of a spec, before its argument: responds-with:429 is a responds-with like the listed responds-with:503
    _respondsWith(spec) {
        const kind = (spec || '').split(':')[0];
        return this._faults.some(f => f.respondsWith && f.spec.split(':')[0] === kind);
    }

    // whether a listed spec needs a plaintext HTTP target, as the service says; a typed one the service refuses itself
    _readsHttp(spec) {
        const listed = this._faults.find(f => f.spec === spec);
        return listed ? listed.readsHttp : this._respondsWith(spec);
    }

    _call(method, params) {
        this.jsonRpc[method](params).then(r => {
            notifier.showInfoMessage(r.result);
            this._refresh();
        }).catch(e => notifier.showErrorMessage(String(e)));
    }
}
customElements.define('qwc-network-faults', QwcNetworkFaults);
