function (address, token, excludedQueries) {
    if (location.href !== address || !document.body || document.designMode === 'on') return null;

    // Account data can be rendered inside the same public page as the catalog.
    const excludedArea = [
        'script', 'style', 'noscript', 'template', 'svg', 'iframe', 'canvas',
        'form', 'label', 'input', 'select', 'textarea', 'option', '[contenteditable]', '[role="form"]',
        '[role="search"]', '[role="searchbox"]', '[role="textbox"]', '[role="combobox"]', '[role="spinbutton"]',
        'header', 'nav', 'footer', 'aside', 'dialog', '[role="dialog"]', '[aria-modal="true"]',
        '[hidden]', '[inert]', '[aria-hidden="true"]', '[translate="no"]',
        '[id*="login" i]', '[class*="login" i]', '[id*="sign-in" i]', '[class*="sign-in" i]',
        '[id*="account" i]', '[class*="account" i]', '[class*="user-info" i]',
        '[class*="userinfo" i]', '[class*="user-profile" i]', '[class*="personal-center" i]',
        '[class*="shopping-cart" i]', '[class*="checkout" i]', '[class*="order-history" i]',
        '[class*="search-input" i]', '[class*="search-keyword" i]', '[class*="search-query" i]'
    ].join(',');
    const contactData = /\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b|(?:\+\d{1,3}[ -]?)?(?:\(?\d{3}\)?[ -])\d{3}[ -]\d{4}\b/i;
    const normalize = text => text.normalize('NFKC').trim().toLowerCase().replace(/\s+/g, ' ');
    const searchTerms = excludedQueries.map(normalize).filter(Boolean);
    const searchWords = new Set(searchTerms.flatMap(term => term.split(' ')));

    function isPublic(node, text) {
        const parent = node.parentElement;
        if (!parent || !node.isConnected || node.ownerDocument !== document || parent.closest(excludedArea)) return false;
        if (contactData.test(text) || searchWords.has(normalize(text))) return false;
        const heading = parent.closest('h1, h2, h3, [role="heading"]');
        return !heading || searchTerms.every(term => !normalize(heading.textContent).includes(term));
    }

    function isVisible(node, range) {
        range.selectNodeContents(node);
        let left = 0;
        let top = 0;
        let right = window.innerWidth;
        let bottom = window.innerHeight;
        for (let element = node.parentElement; element; element = element.parentElement) {
            const style = getComputedStyle(element);
            if (style.visibility !== 'visible' || style.opacity === '0' || style.contentVisibility === 'hidden') return false;
            if (style.overflowX === 'visible' && style.overflowY === 'visible') continue;
            const bounds = element.getBoundingClientRect();
            if (style.overflowX !== 'visible') {
                left = Math.max(left, bounds.left);
                right = Math.min(right, bounds.right);
            }
            if (style.overflowY !== 'visible') {
                top = Math.max(top, bounds.top);
                bottom = Math.min(bottom, bounds.bottom);
            }
        }
        return right > left && bottom > top && Array.from(range.getClientRects()).some(rect =>
            rect.width > 0 && rect.height > 0 && rect.right > left && rect.bottom > top && rect.left < right && rect.top < bottom
        );
    }

    const previous = window.__kitaiPageTranslation;
    if (previous && previous.address !== address) previous.restore();
    const applied = previous && previous.address === address ? previous.applied : new Map();
    for (const [node, entry] of applied) {
        if (!node.isConnected || (node.textContent !== entry.original && node.textContent !== entry.translation)) {
            applied.delete(node);
        }
    }

    const pending = new Map();
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    const range = document.createRange();
    let characters = 0;
    let hasMore = false;
    let visited = 0;
    let pendingNodes = 0;
    while (walker.nextNode() && visited++ < 20_000) {
        const node = walker.currentNode;
        const original = node.textContent;
        const text = original.trim();
        if (text.length > 1_500 || !/[A-Za-z\u3400-\u9fff]/.test(text) || applied.has(node)) continue;
        if (!isPublic(node, text) || !isVisible(node, range)) continue;
        if (applied.size + pendingNodes >= 600) break;
        if (!pending.has(text)) {
            if (pending.size >= 36 || characters + text.length > 4_500) {
                hasMore = true;
                break;
            }
            pending.set(text, []);
            characters += text.length;
        }
        pending.get(text).push({ node, original });
        pendingNodes++;
    }

    function display(showTranslation) {
        let count = 0;
        for (const [node, entry] of applied) {
            if (!isPublic(node, entry.original.trim())) continue;
            const before = showTranslation ? entry.original : entry.translation;
            const after = showTranslation ? entry.translation : entry.original;
            if (node.textContent === before) node.textContent = after;
            if (node.textContent === after) count++;
        }
        return count;
    }

    window.__kitaiPageTranslation = {
        address,
        applied,
        restore: () => display(false),
        apply(expectedAddress, expectedToken, translations) {
            if (location.href !== address || expectedAddress !== address || expectedToken !== token) return 0;
            if (!Array.isArray(translations) || translations.length !== pending.size ||
                translations.some(value => typeof value !== 'string' || !value.trim())) return -1;
            let count = 0;
            let index = 0;
            for (const entries of pending.values()) {
                const text = translations[index++];
                for (const { node, original } of entries) {
                    if (node.textContent !== original || !isPublic(node, original.trim())) continue;
                    const translation = original.match(/^\s*/)[0] + text + original.match(/\s*$/)[0];
                    node.textContent = translation;
                    applied.set(node, { original, translation });
                    count++;
                }
            }
            pending.clear();
            return count;
        },
        toggle(expectedAddress, showTranslation) {
            return location.href === address && expectedAddress === address ? display(showTranslation) : 0;
        }
    };
    return { texts: Array.from(pending.keys()), hasMore };
}
