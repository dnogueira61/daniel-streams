/**
 * Node.js DOM Sandbox Simulation Harness
 * Simulates browser DOM APIs for testing WebView sandbox neutralization scripts.
 */

class MockElement {
    constructor(tagName = 'div') {
        this.tagName = tagName.toUpperCase();
        this.attributes = new Map();
        this._src = '';
    }

    get src() {
        return this._src;
    }

    set src(val) {
        this._src = val;
        this.srcReloaded = (this.srcReloaded || 0) + 1;
    }

    setAttribute(name, value) {
        this.attributes.set(name.toLowerCase(), String(value));
    }

    setAttributeNS(namespace, name, value) {
        this.attributes.set(name.toLowerCase(), String(value));
    }

    getAttribute(name) {
        const val = this.attributes.get(name.toLowerCase());
        return val !== undefined ? val : null;
    }

    hasAttribute(name) {
        return this.attributes.has(name.toLowerCase());
    }

    removeAttribute(name) {
        this.attributes.delete(name.toLowerCase());
    }

    toggleAttribute(name, force) {
        const key = name.toLowerCase();
        if (force === undefined) {
            if (this.attributes.has(key)) {
                this.attributes.delete(key);
                return false;
            } else {
                this.attributes.set(key, '');
                return true;
            }
        } else if (force) {
            this.attributes.set(key, '');
            return true;
        } else {
            this.attributes.delete(key);
            return false;
        }
    }
}

class MockMutationObserver {
    constructor(callback) {
        this.callback = callback;
    }

    observe(target, options) {
        this.target = target;
        this.options = options;
    }

    disconnect() {}

    trigger(mutations) {
        if (this.callback) {
            this.callback(mutations, this);
        }
    }
}

// Global environment setup
global.Element = MockElement;
global.HTMLIFrameElement = class MockIFrame extends MockElement {
    constructor() {
        super('IFRAME');
    }
};
global.window = global;
global.document = {
    createElement: (tag) => new MockElement(tag),
    body: new MockElement('BODY'),
    addEventListener: () => {}
};
global.MutationObserver = MockMutationObserver;

module.exports = {
    MockElement,
    MockMutationObserver
};
