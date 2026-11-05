/*
 * Storage Path Monitor – "Multipath Status" tab of the cluster detail page.
 * Loads GET /plugin/storage-path-monitor/paths with the user's session and renders it. All text from the server is
 * inserted with textContent, never as HTML.
 *
 * Optional URL parameters of the cluster page (see CONTRACT.md): spm-datastore=<id> opens and marks that data store,
 * spm-host=<id> marks that host.
 */
(function () {
	'use strict';

	var REFRESH_MS = 30000;
	var STATE_ORDER = ['down', 'degraded', 'unknown', 'ok'];
	var STATE_LABEL = {ok: 'ok', degraded: 'degraded', down: 'down', unknown: 'unknown'};

	function start(root) {
		if (!root || root.getAttribute('data-spm-init') === '1') {
			return;
		}
		root.setAttribute('data-spm-init', '1');
		var clusterId = root.getAttribute('data-spm-cluster-id');
		var params = new URLSearchParams(window.location.search);
		var focusDatastore = params.get('spm-datastore');
		var focusHost = params.get('spm-host');
		var expanded = {};
		if (focusDatastore) {
			expanded[focusDatastore] = true;
		}
		var table = root.querySelector('.spm-table');
		var tbody = table.querySelector('tbody');
		var message = root.querySelector('.spm-message');
		var summary = root.querySelector('.spm-summary');
		var updated = root.querySelector('.spm-updated');
		var interval = root.querySelector('.spm-interval');
		var orphanBox = root.querySelector('.spm-orphans');
		var orphanBody = orphanBox.querySelector('tbody');
		var scrolled = false;
		var loading = false;
		var sequence = 0;
		var loadedOnce = false;

		function el(tag, className, text) {
			var node = document.createElement(tag);
			if (className) {
				node.className = className;
			}
			if (text !== undefined && text !== null) {
				node.textContent = String(text);
			}
			return node;
		}

		function stateCode(state) {
			return STATE_LABEL.hasOwnProperty(state) ? state : 'unknown';
		}

		function badge(state) {
			var code = stateCode(state);
			return el('span', 'spm-badge spm-' + code, STATE_LABEL[code]);
		}

		function formatTime(iso) {
			if (!iso) {
				return '–';
			}
			var date = new Date(iso);
			return isNaN(date.getTime()) ? iso : date.toLocaleString();
		}

		function countsText(counts) {
			var parts = [];
			STATE_ORDER.forEach(function (code) {
				var n = counts && counts[code];
				if (n) {
					parts.push(n + ' ' + code);
				}
			});
			return parts.length ? parts.join(', ') : 'no hosts measured yet';
		}

		function pathsText(host) {
			if (host.activePaths === null || host.activePaths === undefined) {
				return '–';
			}
			var text = host.activePaths + '/' + host.totalPaths + ' active';
			if (host.expectedPaths && host.expectedPaths > host.totalPaths) {
				text += ' (expected ' + host.expectedPaths + ')';
			}
			return text;
		}

		function mountText(host) {
			if (host.mounted === null || host.mounted === undefined) {
				return '–';
			}
			if (!host.mounted) {
				return 'not mounted';
			}
			if (host.withdrawn) {
				return 'withdrawn';
			}
			return host.poolActive === false ? 'mounted, pool inactive' : 'mounted';
		}

		function withdrawnText(value) {
			if (value === null || value === undefined) {
				return '–';
			}
			return value ? 'yes' : 'no';
		}

		function renderOrphans(orphans) {
			orphanBody.textContent = '';
			orphanBox.hidden = !orphans.length;
			orphans.forEach(function (orphan) {
				var row = el('tr');
				row.appendChild(el('td', null, orphan.name || ('Host ' + orphan.hostId)));
				row.appendChild(el('td', null, orphan.device || '–'));
				row.appendChild(el('td', null, orphan.mountPoint || '–'));
				row.appendChild(el('td', null, withdrawnText(orphan.withdrawn)));
				row.appendChild(el('td', null, formatTime(orphan.since)));
				orphanBody.appendChild(row);
			});
		}

		function hostTable(datastore) {
			var inner = el('table', 'table table-condensed spm-hosts');
			var head = el('thead');
			var headRow = el('tr');
			['Host', 'State', 'Paths', 'Mount', 'Measured', 'Error'].forEach(function (label) {
				headRow.appendChild(el('th', null, label));
			});
			head.appendChild(headRow);
			inner.appendChild(head);
			var body = el('tbody');
			(datastore.hosts || []).forEach(function (host) {
				var row = el('tr', 'spm-host-row');
				row.setAttribute('data-spm-host-id', host.hostId);
				if (focusHost && String(host.hostId) === focusHost && String(datastore.id) === focusDatastore) {
					row.classList.add('spm-focus');
				}
				var nameCell = el('td');
				var label = host.name || ('Host ' + host.hostId);
				if (host.name) {
					var link = el('a', null, label);
					link.href = '/infrastructure/servers/' + encodeURIComponent(host.hostId);
					nameCell.appendChild(link);
				} else {
					nameCell.textContent = label;
				}
				row.appendChild(nameCell);
				var stateCell = el('td');
				stateCell.appendChild(badge(host.state));
				row.appendChild(stateCell);
				row.appendChild(el('td', null, pathsText(host)));
				row.appendChild(el('td', null, mountText(host)));
				row.appendChild(el('td', null, formatTime(host.measuredAt)));
				row.appendChild(el('td', 'spm-error', host.error || ''));
				body.appendChild(row);
			});
			if (!(datastore.hosts || []).length) {
				var empty = el('tr');
				var cell = el('td', 'spm-empty', 'No host measured yet.');
				cell.colSpan = 6;
				empty.appendChild(cell);
				body.appendChild(empty);
			}
			inner.appendChild(body);
			return inner;
		}

		function render(data) {
			var datastores = data.datastores || [];
			tbody.textContent = '';
			interval.textContent = data.intervalSeconds || '–';
			updated.textContent = data.lastRunAt ? 'Last run: ' + formatTime(data.lastRunAt) : 'Waiting for the first run…';
			summary.textContent = '';
			if (data.state) {
				summary.appendChild(badge(data.state));
			}
			renderOrphans(data.orphanMounts || []);
			if (!datastores.length) {
				table.hidden = true;
				message.hidden = false;
				message.textContent = 'This cluster has no shared-LUN data store.';
				return;
			}
			message.hidden = true;
			table.hidden = false;
			datastores.forEach(function (datastore) {
				var id = String(datastore.id);
				var row = el('tr', 'spm-ds-row');
				row.setAttribute('data-spm-datastore-id', id);
				if (id === focusDatastore) {
					row.classList.add('spm-focus');
				}
				var toggleCell = el('td', 'spm-col-toggle');
				var toggle = el('button', 'btn btn-link spm-toggle', expanded[id] ? '▾' : '▸');
				toggle.type = 'button';
				toggle.setAttribute('aria-expanded', expanded[id] ? 'true' : 'false');
				toggle.setAttribute('aria-label', 'Hosts of ' + (datastore.name || id));
				toggleCell.appendChild(toggle);
				row.appendChild(toggleCell);
				var nameCell = el('td');
				var link = el('a', null, datastore.name || ('Data store ' + id));
				link.href = '/infrastructure/storage/data-stores/' + encodeURIComponent(id);
				nameCell.appendChild(link);
				row.appendChild(nameCell);
				var stateCell = el('td');
				stateCell.appendChild(badge(datastore.state));
				row.appendChild(stateCell);
				row.appendChild(el('td', null, countsText(datastore.counts)));
				tbody.appendChild(row);

				var detail = el('tr', 'spm-detail');
				detail.hidden = !expanded[id];
				var detailCell = el('td');
				detailCell.colSpan = 4;
				detailCell.appendChild(hostTable(datastore));
				detail.appendChild(detailCell);
				tbody.appendChild(detail);

				toggle.addEventListener('click', function () {
					expanded[id] = !expanded[id];
					detail.hidden = !expanded[id];
					toggle.textContent = expanded[id] ? '▾' : '▸';
					toggle.setAttribute('aria-expanded', expanded[id] ? 'true' : 'false');
				});
			});
			if (focusDatastore && !scrolled) {
				var target = tbody.querySelector('[data-spm-datastore-id="' + CSS.escape(focusDatastore) + '"]');
				if (target && target.scrollIntoView) {
					scrolled = true;
					target.scrollIntoView({block: 'center'});
				}
			}
		}

		function showError(text) {
			message.hidden = false;
			message.textContent = table.hidden ? text : text + ' Showing the last loaded state.';
		}

		/* One request at a time; a response is only shown if no newer request was started meanwhile. */
		function load() {
			if (loading) {
				return;
			}
			loading = true;
			loadedOnce = true;
			var current = ++sequence;
			var url = '/plugin/storage-path-monitor/paths?clusterId=' + encodeURIComponent(clusterId);
			fetch(url, {credentials: 'same-origin', headers: {Accept: 'application/json'}})
				.then(function (response) {
					var type = response.headers.get('Content-Type') || '';
					if (response.status === 403) {
						throw new Error('You do not have permission to see the path states of this cluster.');
					}
					if (response.status === 404 || type.indexOf('json') < 0) {
						throw new Error('Path states are not available for this cluster.');
					}
					if (!response.ok) {
						throw new Error('Path states could not be loaded (HTTP ' + response.status + ').');
					}
					return response.json();
				})
				.then(function (data) {
					if (current === sequence) {
						render(data);
					}
				})
				.catch(function (error) {
					if (current === sequence) {
						showError(error && error.message ? error.message : 'Path states could not be loaded.');
					}
				})
				.then(function () {
					loading = false;
				});
		}

		function visible() {
			return document.visibilityState !== 'hidden' && root.isConnected && root.offsetParent !== null;
		}

		function schedule() {
			window.setTimeout(function () {
				if (!root.isConnected) {
					return;
				}
				if (visible()) {
					load();
				}
				schedule();
			}, REFRESH_MS);
		}

		root.querySelector('.spm-reload').addEventListener('click', load);
		// Load when the view is opened (Bootstrap tab/pill events go through jQuery); right away if it is already
		// visible or if the events cannot be observed.
		var jq = window.jQuery;
		if (jq) {
			jq(document).on('shown.bs.tab', 'a[href="#' + PANE_ID + '"]', function () {
				if (root.isConnected) {
					load();
				}
			});
		}
		if (!jq || visible()) {
			load();
		}
		// The page may select the view from the link after this script ran
		window.setTimeout(function () {
			if (!loadedOnce && visible()) {
				load();
			}
		}, 500);
		schedule();
	}

	var PANE_ID = 'storage-path-monitor-tab';

	/*
	 * Morpheus adds the plugin tab as a main tab of the cluster page. Where the page has the storage sub-navigation
	 * (Data Stores, Volumes, Storage Interfaces), the tab is moved there as a fourth entry. This depends on the page
	 * structure and is skipped (main tab stays) if that structure is not found. The pane keeps its id, so the link
	 * #!storage-path-monitor-tab opens it in both places (Morpheus also selects sub-entries and their parent tab).
	 * Runs while the page is parsed, before Morpheus evaluates the link.
	 */
	function placeInStorage() {
		var pane = document.getElementById(PANE_ID);
		var storage = document.getElementById('storage');
		if (!pane || !storage || pane.getAttribute('data-spm-placed') === '1') {
			return;
		}
		var pills = storage.querySelector('ul.nav-pills');
		var content = pills && pills.parentNode ? pills.parentNode.querySelector(':scope > .tab-content') : null;
		var mainLink = document.querySelector('ul.nav-tabs > li > a[href="#' + PANE_ID + '"]');
		// Only move if every part is where it is expected; otherwise change nothing
		if (!pills || !content || !mainLink || !mainLink.parentNode || !mainLink.parentNode.parentNode ||
			!pills.querySelector('a[href="#storage-datastores-tab"]')) {
			return;
		}
		var item = document.createElement('li');
		item.setAttribute('role', 'presentation');
		var link = document.createElement('a');
		link.href = '#' + PANE_ID;
		link.setAttribute('data-toggle', 'pill');
		link.setAttribute('role', 'tab');
		link.textContent = 'Multipath Status';
		item.appendChild(link);
		pills.appendChild(item);
		pane.classList.remove('active');
		content.appendChild(pane);
		pane.setAttribute('data-spm-placed', '1');
		mainLink.parentNode.parentNode.removeChild(mainLink.parentNode);
	}

	/* Starts every view on the page that is not running yet (e.g. if Morpheus renders the tab again). */
	function init() {
		var roots = document.querySelectorAll('.spm-root');
		for (var i = 0; i < roots.length; i++) {
			start(roots[i]);
		}
	}

	try {
		placeInStorage();
	} catch (e) {
		// keep the main tab
	}

	if (document.readyState === 'loading') {
		document.addEventListener('DOMContentLoaded', init);
	} else {
		init();
	}
})();
