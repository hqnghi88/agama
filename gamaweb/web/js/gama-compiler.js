/**
 * GAMA Compiler — GAML → JavaScript
 * Tokenizes, parses, and compiles GAML source to JavaScript code.
 * The generated JS depends on runtime globals: Simulation, SpeciesDef, Agent, World.
 *
 * Inspired by NetLogo Tortoise: clean separation between parser and runtime.
 */

// ============================================================
// TOKENIZER — Lexes GAML source into tokens
// ============================================================
function GamlTokenizer(source) {
    this.tokens = [];
    this._tokenize(source);
}

GamlTokenizer.KEYWORDS = new Set([
    'model', 'import', 'as', 'global', 'species', 'grid', 'experiment',
    'init', 'reflex', 'abort', 'do', 'invoke', 'create', 'ask', 'die', 'pause',
    'if', 'else', 'loop', 'while', 'over', 'from', 'to', 'step', 'times',
    'var', 'let', 'action', 'returns', 'return', 'break', 'continue',
    'true', 'false', 'nil', 'NaN', 'inf', 'self', 'myself', 'each', 'super',
    'int', 'float', 'bool', 'string', 'rgb', 'list', 'map', 'point',
    'geometry', 'file', 'container', 'pair', 'matrix',
    'parameter', 'output', 'display', 'monitor', 'layout', 'aspect',
    'type', 'parent', 'skills', 'neighbors', 'width', 'height',
    'number', 'when', 'update', 'max', 'min', 'category',
    'color', 'draw', 'circle', 'square', 'rectangle', 'line',
    'border', 'antialias', 'style', 'background', 'data', 'value',
    'chart', 'series', 'refresh', 'every', 'cycles',
    'among', 'at_distance', 'overlapping', 'inside', 'on',
    'accumulate', 'count', 'length', 'flip', 'rnd', 'one_of',
    'empty', 'copy_of', 'reverse', 'sort_by', 'collect',
    'neighbors_at', 'neighbors', 'closest_points_with', 'distance_to', 'towards', 'of_species',
    'write', 'save', 'to', 'format', 'rewrite', 'header',
    'simulations', 'envelope', 'shape', 'location', 'speed',
    'method', 'tabu', 'maximize', 'minimize', 'iter_max', 'tabu_list_size',
    'until', 'repeat', 'keep_seed', 'batch',
    'diff', 'solve', 'equation',
    'add', 'remove', 'put', 'match', 'switch', 'default', 'try', 'catch',
    'assert', 'error', 'warn', 'status', 'focus_on', 'highlight',
    'capture', 'restore', 'release', 'migrate', 'diffuse',
    'text', 'setup',
    'and', 'or', 'not', 'xor',
]);

GamlTokenizer.prototype._tokenize = function (s) {
    var i = 0;
    while (i < s.length) {
        // Skip whitespace
        if (/\s/.test(s[i])) { i++; continue; }

        // Line comment
        if (s[i] === '/' && s[i + 1] === '/') {
            while (i < s.length && s[i] !== '\n') i++;
            continue;
        }

        // Block comment
        if (s[i] === '/' && s[i + 1] === '*') {
            i += 2;
            while (i < s.length - 1 && !(s[i] === '*' && s[i + 1] === '/')) i++;
            i += 2;
            continue;
        }

        // String
        if (s[i] === '"' || s[i] === "'") {
            var quote = s[i]; i++;
            var str = '';
            while (i < s.length && s[i] !== quote) {
                if (s[i] === '\\') { str += s[++i]; } else { str += s[i]; }
                i++;
            }
            i++; // skip closing quote
            this.tokens.push({ type: 'STRING', value: str });
            continue;
        }

        // Color literal (#name)
        if (s[i] === '#' && i + 1 < s.length && /[a-zA-Z]/.test(s[i + 1])) {
            i++;
            var cn = '';
            while (i < s.length && /[a-zA-Z0-9]/.test(s[i])) { cn += s[i]; i++; }
            this.tokens.push({ type: 'COLOR', value: '#' + cn });
            continue;
        }

        // Number
        if (/[0-9]/.test(s[i]) || (s[i] === '.' && i + 1 < s.length && /[0-9]/.test(s[i + 1]))) {
            var num = '';
            while (i < s.length && /[0-9.]/.test(s[i])) { num += s[i]; i++; }
            this.tokens.push({ type: 'NUMBER', value: parseFloat(num) });
            continue;
        }

        // Identifier or keyword
        if (/[a-zA-Z_]/.test(s[i])) {
            var id = '';
            while (i < s.length && /[a-zA-Z0-9_]/.test(s[i])) { id += s[i]; i++; }
            this.tokens.push({
                type: GamlTokenizer.KEYWORDS.has(id) ? 'KW' : 'ID',
                value: id
            });
            continue;
        }

        // Multi-char operators
        if (s[i] === '<' && s[i + 1] === '-') { this.tokens.push({ type: 'OP', value: '<-' }); i += 2; continue; }
        if (s[i] === '-' && s[i + 1] === '>') { this.tokens.push({ type: 'OP', value: '->' }); i += 2; continue; }
        if (s[i] === ':' && s[i + 1] === ':') { this.tokens.push({ type: 'OP', value: '::' }); i += 2; continue; }
        if (s[i] === '<' && s[i + 1] === '=') { this.tokens.push({ type: 'OP', value: '<=' }); i += 2; continue; }
        if (s[i] === '>' && s[i + 1] === '=') { this.tokens.push({ type: 'OP', value: '>=' }); i += 2; continue; }
        if (s[i] === '!' && s[i + 1] === '=') { this.tokens.push({ type: 'OP', value: '!=' }); i += 2; continue; }
        if (s[i] === '=' && s[i + 1] === '=') { this.tokens.push({ type: 'OP', value: '==' }); i += 2; continue; }

        // Single-char tokens
        this.tokens.push({ type: s[i], value: s[i] });
        i++;
    }
    this.tokens.push({ type: 'EOF', value: null });
};

// ============================================================
// PARSER — Recursive descent, produces AST
// ============================================================
function GamlParser(tokens) {
    this.tokens = tokens;
    this.pos = 0;
}

GamlParser.prototype.peek = function () { return this.tokens[this.pos]; };
GamlParser.prototype.next = function () { return this.tokens[this.pos++]; };
GamlParser.prototype.atEnd = function () { return this.peek().type === 'EOF'; };

GamlParser.prototype.expect = function (type, value) {
    var t = this.next();
    if (t.type !== type || (value !== undefined && t.value !== value)) {
        throw new Error('Parse error: expected ' + type + ' "' + value + '" but got ' + t.type + ' "' + t.value + '" at token ' + (this.pos - 1));
    }
    return t;
};

GamlParser.prototype.match = function (type, value) {
    var t = this.peek();
    if (t.type === type && (value === undefined || t.value === value)) {
        this.pos++;
        return t;
    }
    return null;
};

GamlParser.prototype.matchKW = function (kw) {
    var t = this.peek();
    if (t.type === 'KW' && t.value === kw) { this.pos++; return t; }
    return null;
};

GamlParser.prototype.skipToBrace = function () {
    var depth = 0;
    while (!this.atEnd()) {
        if (this.peek().type === '{') depth++;
        if (this.peek().type === '}') {
            if (depth === 0) return;
            depth--;
        }
        if (this.peek().type === ';' && depth === 0) return;
        this.next();
    }
};

// === Top-level ===

GamlParser.prototype.parse = function () {
    var ast = { type: 'Model', name: '', imports: [], sections: [] };

    // Skip @pragmas
    while (this.peek().type === '@') this.next();

    // model name
    if (this.matchKW('model')) ast.name = this.next().value;

    // imports
    while (this.matchKW('import')) {
        var imp = { path: this.next().value };
        if (this.matchKW('as')) imp.alias = this.next().value;
        ast.imports.push(imp);
    }

    // sections
    while (!this.atEnd()) {
        if (this.peek().type === 'KW' && this.peek().value === 'global') {
            ast.sections.push(this.parseGlobal());
        } else if (this.peek().type === 'KW' && (this.peek().value === 'species' || this.peek().value === 'grid')) {
            ast.sections.push(this.parseSpecies());
        } else if (this.peek().type === 'KW' && this.peek().value === 'experiment') {
            ast.sections.push(this.parseExperiment());
        } else {
            this.next(); // skip unknown
        }
    }
    return ast;
};

GamlParser.prototype.parseGlobal = function () {
    this.next(); // skip 'global'
    var block = { type: 'global', statements: [] };
    if (this.peek().type === '{') {
        this.next();
        while (this.peek().type !== '}' && !this.atEnd()) {
            block.statements.push(this.parseStatement());
        }
        this.match('}');
    }
    return block;
};

GamlParser.prototype.parseSpecies = function () {
    var isGrid = this.peek().value === 'grid';
    this.next(); // skip species/grid
    var sp = {
        type: isGrid ? 'grid' : 'species',
        name: this.next().value,
        facets: {},
        statements: []
    };

    // Parse facets: key: expr until { or ;
    while (this.peek().type !== '{' && this.peek().type !== ';' && !this.atEnd()) {
        var key = this.peek().value;
        if ((this.peek().type === 'ID' || this.peek().type === 'KW') &&
            this.tokens[this.pos + 1] && this.tokens[this.pos + 1].value === ':') {
            this.next(); // key
            this.next(); // skip :
            sp.facets[key] = this.parseExpression();
        } else {
            break;
        }
    }

    if (this.peek().type === '{') {
        this.next();
        while (this.peek().type !== '}' && !this.atEnd()) {
            sp.statements.push(this.parseStatement());
        }
        this.match('}');
    }
    return sp;
};

GamlParser.prototype.parseExperiment = function () {
    this.next(); // skip 'experiment'
    var exp = {
        type: 'experiment',
        name: this.next().value,
        facets: {},
        statements: []
    };

    while (this.peek().type !== '{' && this.peek().type !== ';' && !this.atEnd()) {
        if ((this.peek().type === 'ID' || this.peek().type === 'KW') &&
            this.tokens[this.pos + 1] && this.tokens[this.pos + 1].value === ':') {
            var fk = this.next().value;
            this.next(); // skip :
            exp.facets[fk] = this.parseExpression();
        } else {
            break;
        }
    }

    if (this.peek().type === '{') {
        this.next();
        while (this.peek().type !== '}' && !this.atEnd()) {
            exp.statements.push(this.parseStatement());
        }
        this.match('}');
    }
    return exp;
};

// === Statements ===

GamlParser.prototype.parseStatement = function () {
    var t = this.peek();

    // Variable definition: type name <- expr;  or  type name -> expr;
    if (t.type === 'KW' && ['int', 'float', 'bool', 'string', 'rgb', 'list', 'geometry', 'point',
        'file', 'container', 'pair', 'matrix', 'map', 'number'].indexOf(t.value) >= 0) {
        return this.parseVarDef();
    }

    // Species type vardef: species_name name <- expr; (e.g. "cell close <- ...")
    if (t.type === 'ID' && this.tokens[this.pos + 1] && this.tokens[this.pos + 1].type === 'ID' &&
        this.tokens[this.pos + 2] && (this.tokens[this.pos + 2].value === '<-' || this.tokens[this.pos + 2].value === '->')) {
        return this.parseVarDef();
    }

    // var/let
    if ((t.type === 'KW' && t.value === 'var') || (t.type === 'KW' && t.value === 'let')) {
        this.next();
        return this.parseVarDef();
    }

    // init block
    if (this.matchKW('init')) return this.parseInitBlock();

    // reflex / abort
    if (this.peek().type === 'KW' && (this.peek().value === 'reflex' || this.peek().value === 'abort')) {
        return this.parseReflex();
    }

    // if
    if (this.matchKW('if')) return this.parseIf();

    // loop
    if (this.matchKW('loop')) return this.parseLoop();

    // do / invoke
    if (this.peek().type === 'KW' && (this.peek().value === 'do' || this.peek().value === 'invoke')) {
        return this.parseDo();
    }

    // create
    if (this.matchKW('create')) return this.parseCreate();

    // ask
    if (this.matchKW('ask')) return this.parseAsk();

    // switch
    if (this.matchKW('switch')) return this.parseSwitch();

    // return
    if (this.matchKW('return')) {
        var ret = { type: 'return', expr: null };
        if (this.peek().type !== ';' && this.peek().type !== '}') {
            ret.expr = this.parseExpression();
        }
        this.match(';');
        return ret;
    }

    // write
    if (this.matchKW('write')) {
        var wr = { type: 'write', expr: this.parseExpression() };
        this.match(';');
        return wr;
    }

    // draw (inside aspect blocks)
    if (this.matchKW('draw')) return this.parseDraw();

    // action
    if (this.matchKW('action')) return this.parseAction();

    // Skip display/output/parameter/monitor/layout/chart/equation/solve/method/save/data
    if (this.peek().type === 'KW' && ['display', 'output', 'parameter', 'monitor', 'layout',
        'chart', 'data', 'save', 'equation', 'solve', 'method'].indexOf(this.peek().value) >= 0) {
        this.skipToBrace();
        return { type: 'skip' };
    }

    // aspect block
    if (this.matchKW('aspect')) return this.parseAspect();

    // Expression statement (assignment, etc.)
    var expr = this.parseExpression();

    // Assignment: expr <- expr  or  expr += expr
    if (this.peek().value === '<-') {
        this.next();
        var val = this.parseExpression();
        this.match(';');
        return { type: 'assign', target: expr, value: val };
    }
    if (this.peek().value === '<<' || this.peek().value === '+=') {
        this.next();
        var val2 = this.parseExpression();
        this.match(';');
        return { type: 'add_to', target: expr, value: val2 };
    }

    this.match(';');
    return { type: 'expr_stmt', expr: expr };
};

GamlParser.prototype.parseVarDef = function () {
    var typeTok = this.next(); // type keyword

    // Skip generic type parameters: list<si_grid>, map<string,int>, etc.
    if (['list', 'map', 'pair'].indexOf(typeTok.value) >= 0 && this.peek().type === '<') {
        var depth = 0;
        do {
            if (this.peek().type === '<') depth++;
            if (this.peek().type === '>') depth--;
            this.next();
        } while (depth > 0 && !this.atEnd());
    }

    var name = this.next().value;
    var def = { type: 'vardef', varType: typeTok.value, name: name, init: null, derived: false };

    // Skip attribute facets: min: expr, max: expr, const: true, among: expr, unit: expr, etc.
    while (this.peek().type !== '<-' && this.peek().type !== '->' && this.peek().type !== ';' && this.peek().type !== '}' && !this.atEnd()) {
        if ((this.peek().type === 'ID' || this.peek().type === 'KW') &&
            this.tokens[this.pos + 1] && this.tokens[this.pos + 1].value === ':') {
            this.next(); // key
            this.next(); // skip :
            this.parseExpression(); // consume value (discard)
        } else {
            break;
        }
    }

    if (this.peek().value === '<-') {
        this.next();
        def.init = this.parseExpression();
    } else if (this.peek().value === '->') {
        this.next();
        def.init = this.parseExpression();
        def.derived = true;
    }

    this.match(';');
    return def;
};

GamlParser.prototype.parseInitBlock = function () {
    var block = { type: 'init', statements: [] };
    if (this.peek().type === '{') {
        this.next();
        while (this.peek().type !== '}' && !this.atEnd()) {
            block.statements.push(this.parseStatement());
        }
        this.match('}');
    }
    return block;
};

GamlParser.prototype.parseReflex = function () {
    var rx = { type: 'reflex', name: null, when: null, statements: [] };
    this.next(); // skip reflex/abort
    if (this.peek().type === 'ID') rx.name = this.next().value;
    if (this.matchKW('when')) {
        this.expect(':', ':');
        rx.when = this.parseExpression();
    }
    if (this.peek().type === '{') {
        this.next();
        while (this.peek().type !== '}' && !this.atEnd()) {
            rx.statements.push(this.parseStatement());
        }
        this.match('}');
    } else {
        this.match(';');
    }
    return rx;
};

GamlParser.prototype.parseIf = function () {
    var stmt = { type: 'if', cond: null, then: [], elseStmts: [] };
    this.expect('(', '(');
    stmt.cond = this.parseExpression();
    this.expect(')', ')');
    if (this.peek().type === '{') {
        this.next();
        while (this.peek().type !== '}' && !this.atEnd()) {
            stmt.then.push(this.parseStatement());
        }
        this.match('}');
    } else {
        stmt.then.push(this.parseStatement());
    }
    if (this.matchKW('else')) {
        if (this.peek().type === '{') {
            this.next();
            while (this.peek().type !== '}' && !this.atEnd()) {
                stmt.elseStmts.push(this.parseStatement());
            }
            this.match('}');
        } else {
            stmt.elseStmts.push(this.parseStatement());
        }
    }
    return stmt;
};

GamlParser.prototype.parseLoop = function () {
    var loop = { type: 'loop', name: null, over: null, from: null, to: null, step: null, while_: null, times: null, statements: [] };

    // Optional loop variable name
    if (this.peek().type === 'ID' && this.tokens[this.pos + 1] && this.tokens[this.pos + 1].value !== ':') {
        loop.name = this.next().value;
    }

    // Parse loop facets until {
    while (this.peek().type !== '{' && this.peek().type !== ';' && !this.atEnd()) {
        if (this.matchKW('over')) { this.expect(':', ':'); loop.over = this.parseExpression(); }
        else if (this.matchKW('from')) { this.expect(':', ':'); loop.from = this.parseExpression(); }
        else if (this.matchKW('to')) { this.expect(':', ':'); loop.to = this.parseExpression(); }
        else if (this.matchKW('step')) { this.expect(':', ':'); loop.step = this.parseExpression(); }
        else if (this.matchKW('while')) { this.expect(':', ':'); loop.while_ = this.parseExpression(); }
        else if (this.matchKW('times')) { this.expect(':', ':'); loop.times = this.parseExpression(); }
        else { this.next(); }
    }

    if (this.peek().type === '{') {
        this.next();
        while (this.peek().type !== '}' && !this.atEnd()) {
            loop.statements.push(this.parseStatement());
        }
        this.match('}');
    }
    return loop;
};

GamlParser.prototype.parseDo = function () {
    this.next(); // skip do/invoke
    // Parse target name (just an ID/KW, not a full expression to avoid consuming facet parens)
    var targetName = this.next().value;
    var stmt = { type: 'do', target: { type: 'var', name: targetName }, facets: [] };

    // Parse facets: (key: expr, ...) or key: expr ...
    while (this.peek().type !== ';' && this.peek().type !== '{' && this.peek().type !== '}' && !this.atEnd()) {
        if (this.peek().type === '(') {
            this.next(); // skip (
            while (this.peek().type !== ')' && !this.atEnd()) {
                if ((this.peek().type === 'ID' || this.peek().type === 'KW') &&
                    this.tokens[this.pos + 1] && this.tokens[this.pos + 1].value === ':') {
                    var fk = this.next().value;
                    this.next(); // skip :
                    stmt.facets.push({ key: fk, value: this.parseExpression() });
                } else {
                    break;
                }
                if (this.peek().type === ',') this.next();
            }
            this.expect(')', ')');
        } else if ((this.peek().type === 'ID' || this.peek().type === 'KW') &&
            this.tokens[this.pos + 1] && this.tokens[this.pos + 1].value === ':') {
            var fk = this.next().value;
            this.next(); // skip :
            stmt.facets.push({ key: fk, value: this.parseExpression() });
        } else {
            break;
        }
    }
    this.match(';');
    return stmt;
};

GamlParser.prototype.parseCreate = function () {
    var create = { type: 'create', species: this.parseExpression(), number: null, initStmts: [] };

    // Parse facets until {
    while (this.peek().type !== '{' && this.peek().type !== ';' && !this.atEnd()) {
        if (this.matchKW('number')) {
            this.expect(':', ':');
            create.number = this.parseExpression();
        } else {
            break;
        }
    }

    if (this.peek().type === '{') {
        this.next();
        while (this.peek().type !== '}' && !this.atEnd()) {
            create.initStmts.push(this.parseStatement());
        }
        this.match('}');
    }
    this.match(';');
    return create;
};

GamlParser.prototype.parseAsk = function () {
    var ask = { type: 'ask', target: this.parseExpression(), statements: [] };
    if (this.peek().type === '{') {
        this.next();
        while (this.peek().type !== '}' && !this.atEnd()) {
            ask.statements.push(this.parseStatement());
        }
        this.match('}');
    } else {
        this.match(';');
    }
    return ask;
};

GamlParser.prototype.parseSwitch = function () {
    var sw = { type: 'switch', expr: this.parseExpression(), cases: [], defaultCase: null };
    if (this.peek().type === '{') {
        this.next();
        while (this.peek().type !== '}' && !this.atEnd()) {
            if (this.matchKW('match')) {
                var val = this.parseExpression();
                var stmts = [];
                if (this.peek().type === '{') {
                    this.next();
                    while (this.peek().type !== '}' && !this.atEnd()) {
                        stmts.push(this.parseStatement());
                    }
                    this.match('}');
                }
                sw.cases.push({ value: val, statements: stmts });
            } else if (this.matchKW('default')) {
                sw.defaultCase = [];
                if (this.peek().type === '{') {
                    this.next();
                    while (this.peek().type !== '}' && !this.atEnd()) {
                        sw.defaultCase.push(this.parseStatement());
                    }
                    this.match('}');
                }
            } else {
                this.next();
            }
        }
        this.match('}');
    }
    return sw;
};

GamlParser.prototype.parseAction = function () {
    var act = { type: 'action', name: this.next().value, params: [], statements: [] };
    if (this.match('(')) {
        while (this.peek().type !== ')' && !this.atEnd()) {
            var pType = this.next().value;
            var pName = this.next().value;
            act.params.push({ type: pType, name: pName });
            this.match(',');
        }
        this.expect(')', ')');
    }
    if (this.peek().type === '{') {
        this.next();
        while (this.peek().type !== '}' && !this.atEnd()) {
            act.statements.push(this.parseStatement());
        }
        this.match('}');
    }
    return act;
};

GamlParser.prototype.parseAspect = function () {
    var name = this.next().value;
    var block = { type: 'aspect', name: name, statements: [] };
    if (this.peek().type === '{') {
        this.next();
        while (this.peek().type !== '}' && !this.atEnd()) {
            block.statements.push(this.parseStatement());
        }
        this.match('}');
    }
    return block;
};

GamlParser.prototype.parseDraw = function () {
    var stmt = { type: 'draw', shape: this.parseExpression(), facets: [] };
    // Parse key: expr facets until ; or }
    while (this.peek().type !== ';' && this.peek().type !== '}' && !this.atEnd()) {
        if ((this.peek().type === 'ID' || this.peek().type === 'KW') &&
            this.tokens[this.pos + 1] && this.tokens[this.pos + 1].value === ':') {
            var fk = this.next().value;
            this.next(); // skip :
            stmt.facets.push({ key: fk, value: this.parseExpression() });
        } else {
            break;
        }
    }
    this.match(';');
    return stmt;
};

// === Expressions ===

GamlParser.prototype.parseExpression = function () { return this.parseTernary(); };

GamlParser.prototype.parseTernary = function () {
    var expr = this.parseOr();
    if (this.peek().value === '?') {
        this.next();
        var t = this.parseExpression();
        this.expect(':', ':');
        var f = this.parseExpression();
        return { type: 'ternary', cond: expr, trueExpr: t, falseExpr: f };
    }
    return expr;
};

GamlParser.prototype.parseOr = function () {
    var left = this.parseAnd();
    while (this.peek().type === 'KW' && this.peek().value === 'or') {
        this.next();
        left = { type: 'binary', op: '||', left: left, right: this.parseAnd() };
    }
    return left;
};

GamlParser.prototype.parseAnd = function () {
    var left = this.parseComparison();
    while (this.peek().type === 'KW' && this.peek().value === 'and') {
        this.next();
        left = { type: 'binary', op: '&&', left: left, right: this.parseComparison() };
    }
    return left;
};

GamlParser.prototype.parseComparison = function () {
    var left = this.parseAddSub();
    while (true) {
        var t = this.peek();
        if (t.value === '==' || t.value === '!=') {
            this.next();
            left = { type: 'binary', op: t.value, left: left, right: this.parseAddSub() };
        } else if (t.value === '=' && this.pos + 1 < this.tokens.length && this.tokens[this.pos + 1].value !== '<-') {
            // GAML uses = for equality (not assignment)
            this.next();
            left = { type: 'binary', op: '===', left: left, right: this.parseAddSub() };
        } else if (t.value === '<=' || t.value === '>=') {
            this.next();
            left = { type: 'binary', op: t.value, left: left, right: this.parseAddSub() };
        } else if (t.value === '<') {
            // Check it's not <-
            if (this.pos + 1 < this.tokens.length && this.tokens[this.pos + 1].value === '-') break;
            this.next();
            left = { type: 'binary', op: '<', left: left, right: this.parseAddSub() };
        } else if (t.value === '>') {
            this.next();
            left = { type: 'binary', op: '>', left: left, right: this.parseAddSub() };
        } else if (t.type === 'KW' && t.value === 'towards') {
            this.next();
            left = { type: 'binary', op: 'towards', left: left, right: this.parseAddSub() };
        } else {
            break;
        }
    }
    return left;
};

GamlParser.prototype.parseAddSub = function () {
    var left = this.parseMulDiv();
    while (true) {
        var t = this.peek();
        if (t.value === '+') {
            this.next();
            left = { type: 'binary', op: '+', left: left, right: this.parseMulDiv() };
        } else if (t.value === '-') {
            // Check it's not -> or <- or a negative number
            if (this.pos + 1 < this.tokens.length && this.tokens[this.pos + 1].value === '>') break;
            this.next();
            left = { type: 'binary', op: '-', left: left, right: this.parseMulDiv() };
        } else {
            break;
        }
    }
    return left;
};

GamlParser.prototype.parseMulDiv = function () {
    var left = this.parsePower();
    while (this.peek().value === '*' || this.peek().value === '/') {
        if (this.peek().type === '{' || this.peek().type === '}' || this.peek().type === ';') break;
        var op = this.next().value;
        left = { type: 'binary', op: op, left: left, right: this.parsePower() };
    }
    return left;
};

GamlParser.prototype.parsePower = function () {
    var left = this.parseUnary();
    if (this.peek().value === '^') {
        this.next();
        return { type: 'binary', op: '^', left: left, right: this.parseUnary() };
    }
    return left;
};

GamlParser.prototype.parseUnary = function () {
    if (this.peek().value === '-') {
        this.next();
        return { type: 'unary', op: '-', expr: this.parseUnary() };
    }
    if (this.peek().value === '!') {
        this.next();
        return { type: 'unary', op: '!', expr: this.parseUnary() };
    }
    if (this.peek().type === 'KW' && this.peek().value === 'not') {
        this.next();
        return { type: 'unary', op: 'not', expr: this.parseUnary() };
    }
    return this.parsePostfix();
};

GamlParser.prototype.parsePostfix = function () {
    var base = this.parsePrimary();
    while (true) {
        if (this.peek().type === '[') {
            this.next();
            var idx = this.parseExpression();
            this.expect(']', ']');
            base = { type: 'index', base: base, index: idx };
        } else if (this.peek().type === '.') {
            this.next();
            var field = this.next().value;
            if (this.peek().type === '(') {
                this.next();
                var args = [];
                if (this.peek().type !== ')') {
                    args.push(this.parseExpression());
                    while (this.match(',')) args.push(this.parseExpression());
                }
                this.expect(')', ')');
                base = { type: 'methodcall', base: base, method: field, args: args };
            } else {
                base = { type: 'field', base: base, field: field };
            }
        } else if (this.peek().type === 'KW' && this.peek().value === 'count') {
            this.next();
            this.expect('(', '(');
            var pred = this.parseExpression();
            this.expect(')', ')');
            base = { type: 'count_filtered', collection: base, predicate: pred };
        } else if (this.peek().type === 'KW' && this.peek().value === 'as') {
            this.next();
            this.next(); // skip type name
            if (this.peek().type === '<') {
                var d = 0;
                do {
                    if (this.peek().type === '<') d++;
                    if (this.peek().type === '>') d--;
                    this.next();
                } while (d > 0 && !this.atEnd());
            }
        } else if (this.peek().type === 'KW' && this.peek().value === 'accumulate') {
            this.next();
            this.expect('(', '(');
            var accExpr = this.parseExpression();
            this.expect(')', ')');
            base = { type: 'accumulate', base: base, expr: accExpr };
        } else if (this.peek().type === 'KW' && this.peek().value === 'collect') {
            this.next();
            this.expect('(', '(');
            var collExpr = this.parseExpression();
            this.expect(')', ')');
            base = { type: 'collect_call', base: base, expr: collExpr };
        } else if (this.peek().type === 'KW' && this.peek().value === 'neighbors_at') {
            // GAML: expr neighbors_at(dist) or expr neighbors_at dist
            this.next();
            var nad;
            if (this.peek().type === '(') {
                this.next();
                nad = this.parseExpression();
                this.expect(')', ')');
            } else {
                nad = this.parseUnary();
            }
            base = { type: 'call', name: 'neighbors_at', args: [base, nad] };
        } else if (this.peek().type === 'KW' && this.peek().value === 'of_species') {
            // GAML: expr of_species species_name
            this.next();
            var speciesName = this.next().value;
            base = { type: 'of_species', base: base, species: speciesName };
        } else if (this.peek().type === 'KW' && this.peek().value === 'distance_to') {
            // GAML: expr distance_to other (space syntax, no parens)
            this.next();
            var dtTarget = this.parseUnary();
            base = { type: 'call', name: 'distance_to', args: [base, dtTarget] };
        } else if (this.peek().type === 'KW' && this.peek().value === 'sort_by') {
            // GAML: expr sort_by (comparison)
            this.next();
            this.expect('(', '(');
            var sortKey = this.parseExpression();
            this.expect(')', ')');
            base = { type: 'call', name: 'sort_by', args: [base, sortKey] };
        } else if (this.peek().type === 'ID' && this.peek().type !== '{' &&
                   this.tokens[this.pos + 1] && this.tokens[this.pos + 1].value === '(') {
            // GAML: expr funcName(args) -> funcName(expr, args) — method-call-on-object syntax
            var methodName = this.peek().value;
            // Only handle known built-in functions
            var builtins = ['at_distance', 'overlapping', 'inside', 'on', 'closest_points_with',
                           'neighbors', 'neighbors_at', 'distance_to', 'frequency'];
            if (builtins.indexOf(methodName) >= 0) {
                this.next(); // skip method name
                this.expect('(', '(');
                var margs = [];
                if (this.peek().type !== ')') {
                    margs.push(this.parseExpression());
                    while (this.match(',')) margs.push(this.parseExpression());
                }
                this.expect(')', ')');
                margs.unshift(base); // add base as first argument
                base = { type: 'call', name: methodName, args: margs };
            } else {
                break;
            }
        } else {
            break;
        }
    }
    return base;
};

GamlParser.prototype.parsePrimary = function () {
    var t = this.peek();

    if (t.type === 'NUMBER') { this.next(); return { type: 'number', value: t.value }; }
    if (t.type === 'STRING') { this.next(); return { type: 'string', value: t.value }; }
    if (t.type === 'COLOR') { this.next(); return { type: 'color', value: t.value }; }
    if (t.type === 'KW' && t.value === 'true') { this.next(); return { type: 'bool', value: true }; }
    if (t.type === 'KW' && t.value === 'false') { this.next(); return { type: 'bool', value: false }; }
    if (t.type === 'KW' && t.value === 'nil') { this.next(); return { type: 'nil' }; }
    if (t.type === 'KW' && t.value === 'self') { this.next(); return { type: 'self' }; }
    if (t.type === 'KW' && t.value === 'myself') { this.next(); return { type: 'self' }; }
    if (t.type === 'KW' && t.value === 'each') { this.next(); return { type: 'each' }; }

    // Parenthesized expression
    if (t.type === '(') {
        this.next();
        var expr = this.parseExpression();
        this.expect(')', ')');
        return expr;
    }

    // Array [expr, ...]
    if (t.type === '[') {
        this.next();
        var items = [];
        if (this.peek().type !== ']') {
            items.push(this.parseExpression());
            while (this.match(',')) items.push(this.parseExpression());
        }
        this.expect(']', ']');
        return { type: 'array', items: items };
    }

    // Point {expr, expr}
    if (t.type === '{') {
        this.next();
        var coords = [];
        if (this.peek().type !== '}') {
            coords.push(this.parseExpression());
            while (this.match(',')) coords.push(this.parseExpression());
        }
        this.expect('}', '}');
        return { type: 'point', coords: coords };
    }

    // Identifier or keyword used as identifier
    if (t.type === 'ID' || (t.type === 'KW' && [
        'length', 'flip', 'rnd', 'one_of', 'empty', 'among', 'min', 'max',
        'copy_of', 'reverse', 'sort_by', 'accumulate', 'count',
        'at_distance', 'overlapping', 'inside', 'neighbors_at', 'neighbors',
        'closest_points_with', 'distance_to',
        'abs', 'sqrt', 'sin', 'cos', 'tan', 'exp', 'ln', 'log',
        'round', 'floor', 'ceil', 'int', 'float', 'string', 'rgb',
        'date', 'time', 'cycle', 'current_date',
        'write', 'save', 'to', 'format', 'rewrite', 'header',
        'envelope', 'square', 'circle', 'rectangle', 'line',
        'triangle', 'hexagon', 'star', 'mesh', 'sphere',
        'list',
        'point', 'geometry', 'matrix',
    ].indexOf(t.value) >= 0)) {
        this.next();
        var name = t.value;

        // Skip list<type> cast
        if (name === 'list' && this.peek().type === '<') {
            var d2 = 0;
            do {
                if (this.peek().type === '<') d2++;
                if (this.peek().type === '>') d2--;
                this.next();
            } while (d2 > 0 && !this.atEnd());
        }

        // Function call
        if (this.peek().type === '(') {
            this.next();
            var args = [];
            if (this.peek().type !== ')') {
                args.push(this.parseExpression());
                while (this.match(',')) args.push(this.parseExpression());
            }
            this.expect(')', ')');
            return { type: 'call', name: name, args: args };
        }

        // Variable reference
        return { type: 'var', name: name };
    }

    // Fallback
    this.next();
    return { type: 'unknown', value: t.value };
};

// ============================================================
// CODE GENERATOR — AST → JavaScript
// ============================================================
function GamlCompiler() {
    this._currentSpecies = '';
    this._allSpecies = {};   // name -> true (tracks all compiled species)
    this._globalVars = {};   // name -> true (tracks global variables)
    this._localVars = null;  // stack of local variable sets [{}]
}

GamlCompiler.prototype.compile = function (ast) {
    var globalBlock = null;
    var species = [];
    var experiments = [];

    for (var i = 0; i < ast.sections.length; i++) {
        var s = ast.sections[i];
        if (s.type === 'global') globalBlock = s;
        else if (s.type === 'species' || s.type === 'grid') species.push(s);
        else if (s.type === 'experiment') experiments.push(s);
    }

    // Pre-populate _globalVars so species code can reference them
    if (globalBlock) {
        for (var gi = 0; gi < globalBlock.statements.length; gi++) {
            if (globalBlock.statements[gi].type === 'vardef') {
                this._globalVars[globalBlock.statements[gi].name] = true;
            }
        }
    }

    var js = '';

    // Generate species definitions
    for (var j = 0; j < species.length; j++) {
        js += this._genSpecies(species[j]);
    }

    // Generate world/global
    js += this._genGlobal(globalBlock);

    // Generate init function
    js += 'function _initSimulation(sim) {\n';
    js += '  sim.world = new World();\n';
    js += '  sim.world._sim = sim;\n';
    js += '  sim.world._rng = sim.rng;\n';
    js += '  sim.addAgent("world", sim.world);\n';

    // Apply world variable defaults from species definition
    js += '  var _wd = sim.species["world"];\n';
    js += '  if (_wd) {\n';
    js += '    for (var _vn in _wd.varDefs) {\n';
    js += '      var _vd = _wd.varDefs[_vn];\n';
    js += '      sim.world[_vn] = typeof _vd.default === "function" ? _vd.default.call(sim.world) : _vd.default;\n';
    js += '    }\n';
    js += '  }\n';

    // Set world dimensions from species facets or shape variable
    for (var k = 0; k < species.length; k++) {
        if (species[k].type === 'grid') {
            var gw = species[k].facets['width'];
            var gh = species[k].facets['height'];
            if (gw && gw.type === 'number') js += '  sim.world.width = ' + gw.value + ';\n';
            if (gh && gh.type === 'number') js += '  sim.world.height = ' + gh.value + ';\n';
            js += '  sim.world.shape = { width: sim.world.width, height: sim.world.height };\n';
        }
    }
    js += '  // Derive width/height from shape if set\n';
    js += '  if (sim.world.shape && sim.world.shape.width) {\n';
    js += '    sim.world.width = sim.world.shape.width;\n';
    js += '    sim.world.height = sim.world.shape.height || sim.world.shape.width;\n';
    js += '  }\n';
    js += '  if (!sim.world.shape) sim.world.shape = { width: sim.world.width || 500, height: sim.world.height || 500 };\n';

    // Execute world init
    if (globalBlock) {
        var inits = globalBlock.statements.filter(function (s) { return s.type === 'init'; });
        for (var m = 0; m < inits.length; m++) {
            for (var n = 0; n < inits[m].statements.length; n++) {
                js += '  ' + this._genStmt(inits[m].statements[n]) + '\n';
            }
        }
    }

    js += '  return sim;\n';
    js += '}\n';

    return js;
};

GamlCompiler.prototype._collectLocalVars = function (stmts) {
    var locals = {};
    for (var i = 0; i < stmts.length; i++) {
        if (stmts[i].type === 'vardef') locals[stmts[i].name] = true;
    }
    return locals;
};

GamlCompiler.prototype._findFacet = function (facets, key) {
    if (!facets) return null;
    for (var i = 0; i < facets.length; i++) {
        if (facets[i].key === key) return this._exprToJS(facets[i].value);
    }
    return null;
};

GamlCompiler.prototype._extractDrawInfo = function (stmts) {
    for (var i = 0; i < stmts.length; i++) {
        var s = stmts[i];
        if (s.type === 'draw') {
            var info = { colorExpr: null, sizeExpr: null, condition: null, shapeName: 'circle' };
            // Extract shape name
            if (s.shape && s.shape.type === 'call') {
                info.shapeName = s.shape.name;
            } else if (s.shape && s.shape.type === 'string') {
                info.shapeName = 'text';
            }
            // Extract color facet
            for (var j = 0; j < s.facets.length; j++) {
                if (s.facets[j].key === 'color') {
                    info.colorExpr = this._exprToJS(s.facets[j].value);
                }
            }
            // Extract shape size from circle(N), square(N), etc.
            if (s.shape && s.shape.type === 'call') {
                if ((s.shape.name === 'circle' || s.shape.name === 'square') && s.shape.args.length >= 1) {
                    info.sizeExpr = this._exprToJS(s.shape.args[0]);
                } else if (s.shape.name === 'rectangle' && s.shape.args.length >= 2) {
                    info.sizeExpr = 'Math.max(' + this._exprToJS(s.shape.args[0]) + ',' + this._exprToJS(s.shape.args[1]) + ')';
                }
            }
            // Extract text size from string draw
            if (s.shape && s.shape.type === 'string') {
                info.sizeExpr = '3';
            }
            return info;
        }
        // Handle if(draw ...) wrapping
        if (s.type === 'if') {
            var innerDraw = null;
            for (var k = 0; k < s.then.length; k++) {
                if (s.then[k].type === 'draw') { innerDraw = s.then[k]; break; }
            }
            if (innerDraw) {
                var info2 = { colorExpr: null, sizeExpr: null, condition: this._exprToJS(s.cond), shapeName: 'circle' };
                if (innerDraw.shape && innerDraw.shape.type === 'call') {
                    info2.shapeName = innerDraw.shape.name;
                } else if (innerDraw.shape && innerDraw.shape.type === 'string') {
                    info2.shapeName = 'text';
                }
                for (var m = 0; m < innerDraw.facets.length; m++) {
                    if (innerDraw.facets[m].key === 'color') {
                        info2.colorExpr = this._exprToJS(innerDraw.facets[m].value);
                    }
                }
                if (innerDraw.shape && innerDraw.shape.type === 'call') {
                    if ((innerDraw.shape.name === 'circle' || innerDraw.shape.name === 'square') && innerDraw.shape.args.length >= 1) {
                        info2.sizeExpr = this._exprToJS(innerDraw.shape.args[0]);
                    }
                }
                return info2;
            }
        }
    }
    return null;
};

GamlCompiler.prototype._genSpecies = function (sp) {
    var name = sp.name;
    var isGrid = sp.type === 'grid';
    this._currentSpecies = name;
    this._allSpecies[name] = true;
    var code = '(function() {\n';
    code += '  var def = new SpeciesDef(' + JSON.stringify(name) + ');\n';
    code += '  def.isGrid = ' + isGrid + ';\n';

    if (isGrid) {
        var w = sp.facets['width'];
        var h = sp.facets['height'];
        code += '  def.gridWidth = ' + (w && w.type === 'number' ? w.value : 50) + ';\n';
        code += '  def.gridHeight = ' + (h && h.type === 'number' ? h.value : 50) + ';\n';
    }

    var parentFacet = sp.facets['parent'];
    if (parentFacet) {
        code += '  def.parentName = ' + this._exprToJS(parentFacet) + ';\n';
    }

    for (var i = 0; i < sp.statements.length; i++) {
        var stmt = sp.statements[i];
        if (stmt.type === 'vardef') {
            code += '  def.varDefs[' + JSON.stringify(stmt.name) + '] = { default: ' + this._varDefaultJS(stmt) + ' };\n';
            if (stmt.derived) {
                code += '  def.derivedVars.push({ name: ' + JSON.stringify(stmt.name) + ', compute: function() { return ' + this._exprToJS(stmt.init) + '; } });\n';
            }
        } else if (stmt.type === 'init') {
            code += '  def.initFn = function() {\n';
            var savedLocals = this._localVars;
            this._localVars = (this._localVars || []).concat([this._collectLocalVars(stmt.statements)]);
            for (var j = 0; j < stmt.statements.length; j++) {
                code += '    ' + this._genStmt(stmt.statements[j]) + '\n';
            }
            this._localVars = savedLocals;
            code += '  };\n';
        } else if (stmt.type === 'reflex') {
            var locals = this._collectLocalVars(stmt.statements);
            code += '  def.reflexDefs.push({ name: ' + JSON.stringify(stmt.name || 'anon') + ', ';
            code += 'whenFn: ' + (stmt.when ? 'function() { var _a = this; return ' + this._exprToJS(stmt.when) + '; }' : 'null') + ', ';
            code += 'fn: function() {\n';
            code += '    var _a = this;\n';
            var savedLocals2 = this._localVars;
            this._localVars = (this._localVars || []).concat([locals]);
            for (var k = 0; k < stmt.statements.length; k++) {
                code += '    ' + this._genStmt(stmt.statements[k]) + '\n';
            }
            this._localVars = savedLocals2;
            code += '  } });\n';
        } else if (stmt.type === 'action') {
            var actLocals = this._collectLocalVars(stmt.statements);
            code += '  def.actionDefs[' + JSON.stringify(stmt.name) + '] = function(';
            code += stmt.params.map(function (p) { return p.name; }).join(', ');
            code += ') {\n';
            code += '    var _a = this;\n';
            var savedLocals3 = this._localVars;
            this._localVars = (this._localVars || []).concat([actLocals]);
            for (var l = 0; l < stmt.statements.length; l++) {
                code += '    ' + this._genStmt(stmt.statements[l]) + '\n';
            }
            this._localVars = savedLocals3;
            code += '  };\n';
        } else if (stmt.type === 'aspect') {
            // Extract draw info from aspect block to generate _drawFn
            var drawInfo = this._extractDrawInfo(stmt.statements);
            if (drawInfo) {
                code += '  def._drawFn = function(_a) {\n';
                if (drawInfo.condition) {
                    code += '    if (' + drawInfo.condition + ') {\n';
                }
                code += '      _a.shape = ' + JSON.stringify(drawInfo.shapeName) + ';\n';
                if (drawInfo.colorExpr) {
                    code += '      _a.color = ' + drawInfo.colorExpr + ';\n';
                }
                if (drawInfo.sizeExpr) {
                    code += '      _a.size = ' + drawInfo.sizeExpr + ';\n';
                }
                if (drawInfo.condition) {
                    code += '    }\n';
                }
                code += '  };\n';
            }
        }
    }

    code += '  sim.species[' + JSON.stringify(name) + '] = def;\n';
    code += '  sim.speciesOrder.push(' + JSON.stringify(name) + ');\n';
    code += '})();\n';
    return code;
};

GamlCompiler.prototype._genGlobal = function (globalBlock) {
    this._currentSpecies = 'world';
    this._allSpecies['world'] = true;
    var code = '(function() {\n';
    code += '  var def = new SpeciesDef("world");\n';

    if (globalBlock) {
        for (var i = 0; i < globalBlock.statements.length; i++) {
            var stmt = globalBlock.statements[i];
            if (stmt.type === 'vardef') {
                this._globalVars[stmt.name] = true;
                if (!stmt.derived) {
                    code += '  def.varDefs[' + JSON.stringify(stmt.name) + '] = { default: ' + this._varDefaultJS(stmt) + ' };\n';
                } else {
                    code += '  def.derivedVars.push({ name: ' + JSON.stringify(stmt.name) + ', compute: function() { return ' + this._exprToJS(stmt.init) + '; } });\n';
                }
            } else if (stmt.type === 'reflex') {
                var locals = this._collectLocalVars(stmt.statements);
                code += '  def.reflexDefs.push({ name: ' + JSON.stringify(stmt.name || 'anon') + ', ';
                code += 'whenFn: ' + (stmt.when ? 'function() { var _a = this; return ' + this._exprToJS(stmt.when) + '; }' : 'null') + ', ';
                code += 'fn: function() {\n';
                code += '    var _a = this;\n';
                var savedLocals = this._localVars;
                this._localVars = (this._localVars || []).concat([locals]);
                for (var j = 0; j < stmt.statements.length; j++) {
                    code += '    ' + this._genStmt(stmt.statements[j]) + '\n';
                }
                this._localVars = savedLocals;
                code += '  } });\n';
            }
        }
    }

    code += '  sim.species["world"] = def;\n';
    code += '  sim.speciesOrder.push("world");\n';
    code += '})();\n';
    return code;
};

GamlCompiler.prototype._varDefaultJS = function (stmt) {
    if (stmt.init) {
        // Wrap in a function so it's evaluated per-agent (for rnd, flip, etc.)
        var js = this._exprToJS(stmt.init);
        if (js.indexOf('sim.rng') >= 0 || js.indexOf('_rng') >= 0) {
            return 'function() { return ' + js + '; }';
        }
        return js;
    }
    switch (stmt.varType) {
        case 'int': return '0';
        case 'float': return '0.0';
        case 'bool': return 'false';
        case 'string': return '""';
        case 'rgb': return '"#808080"';
        case 'number': return '0';
        default: return 'null';
    }
};

GamlCompiler.prototype._genStmt = function (stmt) {
    if (!stmt) return '';
    switch (stmt.type) {
        case 'vardef':
            return 'var ' + stmt.name + ' = ' + (stmt.init ? this._exprToJS(stmt.init) : 'null') + ';';
        case 'assign':
            var targetJS = this._exprToJS(stmt.target);
            var valJS2 = this._exprToJS(stmt.value);
            // Handle location assignments
            if (stmt.target && stmt.target.type === 'var' && stmt.target.name === 'location') {
                return 'var _loc = ' + valJS2 + '; if (_loc && typeof _loc.x === "number") { _a.x = _loc.x; _a.y = _loc.y; _a.location.x = _loc.x; _a.location.y = _loc.y; }';
            }
            return targetJS + ' = ' + valJS2 + ';';
        case 'add_to':
            return this._exprToJS(stmt.target) + '.push(' + this._exprToJS(stmt.value) + ');';
        case 'if':
            var c = 'if (' + this._exprToJS(stmt.cond) + ') {\n';
            for (var i = 0; i < stmt.then.length; i++) c += '  ' + this._genStmt(stmt.then[i]) + '\n';
            c += '}';
            if (stmt.elseStmts.length > 0) {
                c += ' else {\n';
                for (var j = 0; j < stmt.elseStmts.length; j++) c += '  ' + this._genStmt(stmt.elseStmts[j]) + '\n';
                c += '}';
            }
            return c;
        case 'loop': return this._genLoop(stmt);
        case 'do': return this._genDo(stmt);
        case 'create': return this._genCreate(stmt);
        case 'ask': return this._genAsk(stmt);
        case 'switch': return this._genSwitch(stmt);
        case 'return': return 'return ' + (stmt.expr ? this._exprToJS(stmt.expr) : '') + ';';
        case 'write': return 'console.log(' + this._exprToJS(stmt.expr) + ');';
        case 'expr_stmt': return this._exprToJS(stmt.expr) + ';';
        case 'skip': return '';
        default: return '/* ' + stmt.type + ' */';
    }
};

GamlCompiler.prototype._genLoop = function (stmt) {
    var code = '';
    var loopLocals = {};
    if (stmt.name) loopLocals[stmt.name] = true;
    if (stmt.over && stmt.while_) {
        var v0 = stmt.name || '_lv';
        loopLocals[v0] = true;
        code += 'var _co = ' + this._exprToJS(stmt.over) + ';\n';
        code += 'for (var _li = 0; _li < _co.length; _li++) {\n';
        code += '  var ' + v0 + ' = _co[_li];\n';
        code += '  if (!(' + this._exprToJS(stmt.while_) + ')) continue;\n';
        var savedLocals0 = this._localVars;
        this._localVars = (this._localVars || []).concat([loopLocals]);
        for (var m = 0; m < stmt.statements.length; m++) code += '  ' + this._genStmt(stmt.statements[m]) + '\n';
        this._localVars = savedLocals0;
        code += '}';
    } else if (stmt.over) {
        var v = stmt.name || '_lv';
        loopLocals[v] = true;
        code += 'var _co = ' + this._exprToJS(stmt.over) + ';\n';
        code += 'for (var _li = 0; _li < _co.length; _li++) {\n';
        code += '  var ' + v + ' = _co[_li];\n';
        var savedLocals = this._localVars;
        this._localVars = (this._localVars || []).concat([loopLocals]);
        for (var i = 0; i < stmt.statements.length; i++) code += '  ' + this._genStmt(stmt.statements[i]) + '\n';
        this._localVars = savedLocals;
        code += '}';
    } else if (stmt.while_) {
        code += 'while (' + this._exprToJS(stmt.while_) + ') {\n';
        var savedLocals2 = this._localVars;
        this._localVars = (this._localVars || []).concat([loopLocals]);
        for (var j = 0; j < stmt.statements.length; j++) code += '  ' + this._genStmt(stmt.statements[j]) + '\n';
        this._localVars = savedLocals2;
        code += '}';
    } else if (stmt.times) {
        code += 'for (var _ti = 0; _ti < ' + this._exprToJS(stmt.times) + '; _ti++) {\n';
        var savedLocals3 = this._localVars;
        this._localVars = (this._localVars || []).concat([loopLocals]);
        for (var k = 0; k < stmt.statements.length; k++) code += '  ' + this._genStmt(stmt.statements[k]) + '\n';
        this._localVars = savedLocals3;
        code += '}';
    } else if (stmt.from !== null && stmt.to !== null) {
        var v2 = stmt.name || '_i';
        loopLocals[v2] = true;
        var step = stmt.step ? this._exprToJS(stmt.step) : '1';
        code += 'for (var ' + v2 + ' = ' + this._exprToJS(stmt.from) + '; ' + v2 + ' <= ' + this._exprToJS(stmt.to) + '; ' + v2 + ' += ' + step + ') {\n';
        var savedLocals4 = this._localVars;
        this._localVars = (this._localVars || []).concat([loopLocals]);
        for (var l = 0; l < stmt.statements.length; l++) code += '  ' + this._genStmt(stmt.statements[l]) + '\n';
        this._localVars = savedLocals4;
        code += '}';
    }
    return code;
};

GamlCompiler.prototype._genDo = function (stmt) {
    // Handle known actions
    if (stmt.target && stmt.target.type === 'var') {
        if (stmt.target.name === 'die') return 'this.die();';
        if (stmt.target.name === 'wander') {
            var spd = this._findFacet(stmt.facets, 'speed');
            return 'this.wander(' + (spd || '') + ');';
        }
        if (stmt.target.name === 'pause') return 'sim.pause();';
    }
    if (stmt.target && stmt.target.type === 'call') {
        if (stmt.target.name === 'die') return 'this.die();';
        if (stmt.target.name === 'wander') {
            var spd2 = this._findFacet(stmt.facets, 'speed');
            return 'this.wander(' + (spd2 || '') + ');';
        }
        if (stmt.target.name === 'pause') return 'sim.pause();';
        if (stmt.target.name === 'goto') {
            // do goto(target: expr, speed: expr)
            var targetVal = null, speedVal = null;
            if (stmt.facets) {
                for (var fi = 0; fi < stmt.facets.length; fi++) {
                    if (stmt.facets[fi].key === 'target') targetVal = this._exprToJS(stmt.facets[fi].value);
                    if (stmt.facets[fi].key === 'speed') speedVal = this._exprToJS(stmt.facets[fi].value);
                }
            }
            if (targetVal) {
                var code = 'var _tgt = ' + targetVal + ';\n';
                if (speedVal) code += 'var _spd = ' + speedVal + ';\n';
                code += 'if (_tgt && typeof _tgt.x === "number") {\n';
                code += '  var _dx = _tgt.x - _a.x, _dy = _tgt.y - _a.y;\n';
                code += '  var _d = Math.sqrt(_dx*_dx+_dy*_dy);\n';
                code += '  var _s = ' + (speedVal || '1') + ';\n';
                code += '  if (_d <= _s) { _a.x = _tgt.x; _a.y = _tgt.y; _a.location.x = _tgt.x; _a.location.y = _tgt.y; }\n';
                code += '  else { _a.x += _dx/_d*_s; _a.y += _dy/_d*_s; _a.location.x = _a.x; _a.location.y = _a.y; }\n';
                code += '}';
                return code;
            }
        }
    }
    // Generic do: call as method on self
    var target = this._exprToJS(stmt.target);
    // Strip _a. prefix (agent variable) and function call syntax
    target = target.replace(/^_a\./, '');
    target = target.replace(/\(.*\)/, '');
    if (stmt.facets && stmt.facets.length > 0) {
        var facetStr = stmt.facets.map(function (f) { return f.key + ': ' + this._exprToJS(f.value); }.bind(this)).join(', ');
        return 'if (this.' + target + ') this.' + target + '({' + facetStr + '});';
    }
    return 'if (this.' + target + ') this.' + target + '();';
};

GamlCompiler.prototype._genCreate = function (stmt) {
    // In GAML `create`, the species reference is a name, not a population
    var spExpr = stmt.species.type === 'var' ? JSON.stringify(stmt.species.name) : this._exprToJS(stmt.species);
    var numExpr = stmt.number ? this._exprToJS(stmt.number) : '1';
    var code = 'sim.createAgents(' + spExpr + ', ' + numExpr + ', function(ag) {\n';
    var savedLocals = this._localVars;
    this._localVars = (this._localVars || []).concat([{ ag: true }]);
    for (var i = 0; i < stmt.initStmts.length; i++) {
        var initStmt = stmt.initStmts[i];
        if (initStmt.type === 'assign' && initStmt.target && initStmt.target.type === 'var') {
            var valJS = this._exprToJS(initStmt.value);
            if (initStmt.target.name === 'location') {
                // location <- expr: extract x,y from result
                code += '  var _loc = ' + valJS + ';\n';
                code += '  if (_loc && typeof _loc.x === "number") { ag.x = _loc.x; ag.y = _loc.y; ag.location.x = _loc.x; ag.location.y = _loc.y; }\n';
            } else {
                code += '  ag.' + initStmt.target.name + ' = ' + valJS + ';\n';
            }
        } else {
            code += '  ' + this._genStmt(initStmt) + '\n';
        }
    }
    this._localVars = savedLocals;
    code += '});';
    return code;
};

GamlCompiler.prototype._genAsk = function (stmt) {
    var targetExpr = this._exprToJS(stmt.target);
    var locals = this._collectLocalVars(stmt.statements);
    var code = '(function() {\n';
    code += '  var _at = ' + targetExpr + ';\n';
    code += '  var _al = Array.isArray(_at) ? _at : (_at ? [_at] : []);\n';
    code += '  for (var _ai = 0; _ai < _al.length; _ai++) {\n';
    code += '    var _a = _al[_ai];\n';
    var savedLocals = this._localVars;
    this._localVars = (this._localVars || []).concat([locals]);
    for (var i = 0; i < stmt.statements.length; i++) {
        code += '    ' + this._genStmt(stmt.statements[i]) + '\n';
    }
    this._localVars = savedLocals;
    code += '  }\n';
    code += '})();';
    return code;
};

GamlCompiler.prototype._genSwitch = function (stmt) {
    var exprJS = this._exprToJS(stmt.expr);
    var code = '(function() { var _sv = ' + exprJS + ';\n';
    for (var i = 0; i < stmt.cases.length; i++) {
        var caseVal = this._exprToJS(stmt.cases[i].value);
        code += '  if (_sv === ' + caseVal + ') {\n';
        for (var j = 0; j < stmt.cases[i].statements.length; j++) {
            code += '    ' + this._genStmt(stmt.cases[i].statements[j]) + '\n';
        }
        code += '  } else ';
    }
    if (stmt.defaultCase) {
        code += '{\n';
        for (var k = 0; k < stmt.defaultCase.length; k++) {
            code += '    ' + this._genStmt(stmt.defaultCase[k]) + '\n';
        }
        code += '  }\n';
    } else {
        code += ' {}\n';
    }
    code += '})();';
    return code;
};

// === Expression to JavaScript ===

GamlCompiler.prototype._exprToJS = function (expr) {
    if (!expr) return 'null';
    switch (expr.type) {
        case 'number': return String(expr.value);
        case 'string': return JSON.stringify(expr.value);
        case 'bool': return expr.value ? 'true' : 'false';
        case 'color': {
            var c = expr.value;
            var cssColors = {
                '#red':'#ff0000','#blue':'#0000ff','#green':'#00ff00','#yellow':'#ffff00',
                '#orange':'#ffa500','#purple':'#800080','#pink':'#ffc0cb','#brown':'#a52a2a',
                '#white':'#ffffff','#black':'#000000','#gray':'#808080','#grey':'#808080',
                '#cyan':'#00ffff','#magenta':'#ff00ff','#maroon':'#800000','#navy':'#000080',
                '#teal':'#008080','#olive':'#808000','#silver':'#c0c0c0',
            };
            return JSON.stringify(cssColors[c] || c);
        }
        case 'nil': return 'null';
        case 'self': return '_a';
        case 'each': return '_each';
        case 'var': return this._varRef(expr.name);
        case 'binary': return this._binaryToJS(expr);
        case 'unary': return this._unaryToJS(expr);
        case 'ternary':
            return '(' + this._exprToJS(expr.cond) + '?' + this._exprToJS(expr.trueExpr) + ':' + this._exprToJS(expr.falseExpr) + ')';
        case 'call': return this._callToJS(expr);
        case 'field': return this._exprToJS(expr.base) + '.' + expr.field;
        case 'index': return this._exprToJS(expr.base) + '[' + this._exprToJS(expr.index) + ']';
        case 'methodcall': return this._methodcallToJS(expr);
        case 'count_filtered': return this._countFilteredToJS(expr);
        case 'accumulate': return '(function(){var _b=' + this._exprToJS(expr.base) + ';return Array.isArray(_b)?_b:[_b];})()';
        case 'collect_call': return '(' + this._exprToJS(expr.base) + ').map(function(_each){return ' + this._exprToJS(expr.expr) + ';})';
        case 'of_species': return '(function(){var _c=' + this._exprToJS(expr.base) + ';if(!Array.isArray(_c))return[];return _c.filter(function(a){return a._species===' + JSON.stringify(expr.species) + ';});})()';
        case 'array': return '[' + expr.items.map(this._exprToJS.bind(this)).join(',') + ']';
        case 'point': return '{x:' + expr.coords.map(this._exprToJS.bind(this)).join(',y:') + '}';
        default: return '/*?' + expr.type + '*/';
    }
};

GamlCompiler.prototype._binaryToJS = function (expr) {
    var l = this._exprToJS(expr.left);
    var r = this._exprToJS(expr.right);
    if (expr.op === '=') return '(' + l + '===' + r + ')';
    if (expr.op === '^') return 'Math.pow(' + l + ',' + r + ')';
    if (expr.op === 'towards') return 'GamaBuiltins.gama_heading(' + l + '.x||0,' + l + '.y||0,' + r + '.x||0,' + r + '.y||0)';
    return '(' + l + expr.op + r + ')';
};

GamlCompiler.prototype._unaryToJS = function (expr) {
    var e = this._exprToJS(expr.expr);
    if (expr.op === 'not') return '(!' + e + ')';
    return '(' + expr.op + e + ')';
};

GamlCompiler.prototype._varRef = function (name) {
    // Built-in variables
    if (name === 'self' || name === 'myself') return '_a';
    if (name === 'each') return '_each';
    if (name === 'cycle' || name === 'time') return 'sim.cycle';
    if (name === 'location') return '_a.location';
    if (name === 'color') return '_a.color';
    if (name === 'size') return '_a.size';
    if (name === 'speed') return '_a.speed';
    if (name === 'shape') return 'sim.world.shape';
    if (name === 'width') return 'sim.world.width';
    if (name === 'height') return 'sim.world.height';

    // Known local variable (innermost scope first)
    if (this._localVars) {
        for (var i = this._localVars.length - 1; i >= 0; i--) {
            if (this._localVars[i][name]) return name;
        }
    }

    // Known species name → population reference
    if (this._allSpecies[name]) {
        return '(sim.populations[' + JSON.stringify(name) + ']||[])';
    }

    // Known global variable → world variable
    if (this._globalVars[name]) {
        return 'sim.world.' + name;
    }

    // Agent variable
    return '_a.' + name;
};

GamlCompiler.prototype._callToJS = function (expr) {
    var name = expr.name;
    var args = expr.args.map(this._exprToJS.bind(this));

    switch (name) {
        // Collection operations
        case 'length': return '(function(v){return v==null?0:Array.isArray(v)?v.length:typeof v==="string"?v.length:0;})(' + args[0] + ')';
        case 'empty': return '(!' + args[0] + '||' + args[0] + '.length===0)';
        case 'not_empty': return '(' + args[0] + '&&' + args[0] + '.length>0)';
        case 'first': return 'GamaBuiltins.gama_first(' + args[0] + ')';
        case 'last': return 'GamaBuiltins.gama_last(' + args[0] + ')';
        case 'second': return 'GamaBuiltins.gama_second(' + args[0] + ')';
        case 'third': return 'GamaBuiltins.gama_third(' + args[0] + ')';
        case 'nth': return 'GamaBuiltins.gama_nth(' + args[0] + ',' + args[1] + ')';
        case 'any': return 'GamaBuiltins.gama_any(' + args[0] + ')';
        case 'all': return 'GamaBuiltins.gama_any(' + args[0] + ')';
        case 'none': return 'GamaBuiltins.gama_none(' + args[0] + ')';
        case 'contains': return 'GamaBuiltins.gama_contains(' + args[0] + ',' + args[1] + ')';
        case 'index_of': return 'GamaBuiltins.gama_index_of(' + args[0] + ',' + args[1] + ')';
        case 'last_index_of': return 'GamaBuiltins.gama_last_index_of(' + args[0] + ',' + args[1] + ')';
        case 'sum': return 'GamaBuiltins.gama_sum(' + args[0] + ')';
        case 'mean': return 'GamaBuiltins.gama_mean(' + args[0] + ')';
        case 'product': return 'GamaBuiltins.gama_product(' + args[0] + ')';
        case 'variance': return 'GamaBuiltins.gama_variance(' + args[0] + ')';
        case 'std_dev': return 'GamaBuiltins.gama_std_dev(' + args[0] + ')';
        case 'copy_of': return 'GamaBuiltins.gama_copy_of(' + args[0] + ')';
        case 'reverse': return 'GamaBuiltins.gama_reverse(' + args[0] + ')';
        case 'sort': return 'GamaBuiltins.gama_sort(' + args[0] + ')';
        case 'sort_by': return '(' + args[0] + ').slice().sort(function(a,b){return (function(_each){return ' + args[1] + ';})(a)-(function(_each){return ' + args[1] + ';})(b);})';
        case 'shuffle': return 'sim.rng.shuffle(' + args[0] + ')';
        case 'accumulate': return '(function(){var _b=' + args[0] + ';return Array.isArray(_b)?_b:[_b];})()';
        case 'collect': return '(' + args[0] + ').map(function(_each){return ' + args[1] + ';})';

        // Type casting
        case 'int': return 'GamaBuiltins.gama_int(' + args[0] + ')';
        case 'float': return 'GamaBuiltins.gama_float(' + args[0] + ')';
        case 'string': return 'GamaBuiltins.gama_string(' + args[0] + ')';
        case 'rgb': return 'GamaBuiltins.gama_rgb(' + args.join(',') + ')';

        // Math
        case 'abs': return 'GamaBuiltins.gama_abs(' + args[0] + ')';
        case 'sqrt': return 'GamaBuiltins.gama_sqrt(' + args[0] + ')';
        case 'sin': return 'GamaBuiltins.gama_sin(' + args[0] + ')';
        case 'cos': return 'GamaBuiltins.gama_cos(' + args[0] + ')';
        case 'tan': return 'GamaBuiltins.gama_tan(' + args[0] + ')';
        case 'exp': return 'GamaBuiltins.gama_exp(' + args[0] + ')';
        case 'ln': return 'GamaBuiltins.gama_ln(' + args[0] + ')';
        case 'log': return 'GamaBuiltins.gama_log(' + args[0] + ')';
        case 'round': return 'GamaBuiltins.gama_round(' + args[0] + ')';
        case 'floor': return 'GamaBuiltins.gama_floor(' + args[0] + ')';
        case 'ceil': return 'GamaBuiltins.gama_ceil(' + args[0] + ')';
        case 'mod': return 'GamaBuiltins.gama_mod(' + args[0] + ',' + args[1] + ')';
        case 'div': return 'GamaBuiltins.gama_div(' + args[0] + ',' + args[1] + ')';
        case 'gauss': return args.length > 1 ? 'GamaBuiltins.gama_gauss(' + args[0] + ',' + args[1] + ')' : 'GamaBuiltins.gama_gauss(' + args[0] + ')';
        case 'between': return 'GamaBuiltins.gama_between(' + args[0] + ',' + args[1] + ',' + args[2] + ')';
        case 'lognormal': return args.length > 1 ? 'GamaBuiltins.gama_lognormal(' + args[0] + ',' + args[1] + ')' : 'GamaBuiltins.gama_lognormal(' + args[0] + ')';
        case 'poisson': return 'GamaBuiltins.gama_poisson(' + args[0] + ')';

        // String
        case 'uppercase': return 'GamaBuiltins.gama_uppercase(' + args[0] + ')';
        case 'lowercase': return 'GamaBuiltins.gama_lowercase(' + args[0] + ')';
        case 'replace': return 'GamaBuiltins.gama_replace(' + args[0] + ',' + args[1] + ',' + args[2] + ')';
        case 'replace_all': return 'GamaBuiltins.gama_replace_all(' + args[0] + ',' + args[1] + ',' + args[2] + ')';
        case 'matches': return 'GamaBuiltins.gama_matches(' + args[0] + ',' + args[1] + ')';
        case 'split': return 'GamaBuiltins.gama_split(' + args[0] + ',' + args[1] + ')';
        case 'trim': return 'GamaBuiltins.gama_trim(' + args[0] + ')';
        case 'pad_left': return 'GamaBuiltins.gama_pad_left(' + args[0] + ',' + args[1] + ',' + args[2] + ')';
        case 'intersperse': return 'GamaBuiltins.gama_intersperse(' + args[0] + ',' + args[1] + ')';
        case 'length_str': return 'GamaBuiltins.gama_length_str(' + args[0] + ')';

        // Spatial
        case 'distance_to': return '(function(_o){return GamaBuiltins.gama_distance(_a.x,_a.y,_o.x,_o.y);})(' + (args[1] || args[0]) + ')';
        case 'distance_agents': return 'GamaBuiltins.gama_distance_agents(' + args[0] + ',' + args[1] + ')';
        case 'angle': return 'GamaBuiltins.gama_angle(' + args[0] + ',' + args[1] + ')';
        case 'heading': return 'GamaBuiltins.gama_heading(' + args[0] + ')';
        case 'at_distance':
            return '(function(){var _d=' + args[1] + ';var _p=sim.populations[_a._species]||[];return _p.filter(function(b){var dx=_a.x-b.x;var dy=_a.y-b.y;return Math.sqrt(dx*dx+dy*dy)<=_d;});})()';
        case 'overlapping':
        case 'inside':
            return '(function(){var _s=' + args[0] + ';return Array.isArray(_s)?_s.slice():(sim.populations[String(_s)]||[]).slice();})()';
        case 'neighbors_at':
            return '(function(){var _dist=' + args[1] + ';var _pop=sim.populations[_a._species]||[];return _pop.filter(function(b){if(b===_a||b._dead)return false;var dx=_a.x-b.x;var dy=_a.y-b.y;return Math.sqrt(dx*dx+dy*dy)<=_dist;});})()';
        case 'envelope': return '(function(){var _g=' + args[0] + ';return _g&&_g.width?_g:{width:500,height:500};})()';
        case 'square': return '{width:' + args[0] + ',height:' + args[0] + '}';
        case 'circle': return '{radius:' + args[0] + '}';
        case 'dead': return '(function(_ag){return !_ag||_ag._dead;})(' + args[0] + ')';

        // Utilities
        case 'flip': return 'sim.rng.flip(' + args[0] + ')';
        case 'rnd': return args.length > 1 ? 'sim.rng.rnd(' + args.join(',') + ')' : 'sim.rng.rnd(' + args[0] + ')';
        case 'one_of': return 'sim.rng.one_of(' + args[0] + ')';
        case 'among': return 'sim.rng.shuffle(' + args[1] + ').slice(0,' + args[0] + ')';
        case 'count': return '(sim.populations[_a._species]||[]).filter(function(a){return ' + args[0] + ';}).length';
        case 'min': return args.length > 1 ? 'Math.min(' + args.join(',') + ')' : 'sim.rng.min_list(' + args[0] + ')';
        case 'max': return args.length > 1 ? 'Math.max(' + args.join(',') + ')' : 'sim.rng.max_list(' + args[0] + ')';
        case 'write': return 'console.log(' + args.join(',') + ')';

        default:
            if (this._allSpecies[name]) {
                return '(sim.populations[' + JSON.stringify(name) + ']||[])';
            }
            if (this._globalVars[name]) {
                return 'sim.world.' + name;
            }
            return '(function(){ throw new Error("Unknown: ' + name + '"); })()';
    }
};

GamlCompiler.prototype._methodcallToJS = function (expr) {
    var base = this._exprToJS(expr.base);
    var args = expr.args.map(this._exprToJS.bind(this));

    // .add(val) → push
    if (expr.method === 'add') return base + '.push(' + args.join(',') + ')';
    // .length
    if (expr.method === 'length') return base + '.length';
    // .remove(val)
    if (expr.method === 'remove') {
        return '(function(){var _i=' + base + '.indexOf(' + args[0] + ');if(_i>=0)' + base + '.splice(_i,1);return ' + args[0] + ';})()';
    }

    return base + '.' + expr.method + '(' + args.join(',') + ')';
};

GamlCompiler.prototype._countFilteredToJS = function (expr) {
    var coll = this._exprToJS(expr.collection);
    var pred = this._exprToJS(expr.predicate);
    return '(function(){var _c=' + coll + ';if(!Array.isArray(_c))_c=[];return _c.filter(function(a){return ' + pred + ';}).length;})()';
};

// ============================================================
// PUBLIC API
// ============================================================

function compileGaml(source) {
    var tokens = new GamlTokenizer(source);
    var parser = new GamlParser(tokens.tokens);
    var ast = parser.parse();
    var compiler = new GamlCompiler();
    var jsCode = compiler.compile(ast);
    return { ast: ast, jsCode: jsCode };
}

// Export
window.GamlTokenizer = GamlTokenizer;
window.GamlParser = GamlParser;
window.GamlCompiler = GamlCompiler;
window.compileGaml = compileGaml;
