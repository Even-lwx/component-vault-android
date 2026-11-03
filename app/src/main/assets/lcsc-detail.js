(function () {
    try {
        if (!document.body) return '';
        var tables = document.querySelectorAll('table');
        var parameters = null;
        for (var i = 0; i < tables.length; i++) {
            var rows = tables[i].querySelectorAll('tr');
            var hasCategory = false;
            var hasHeader = false;
            for (var j = 0; j < rows.length; j++) {
                var cells = rows[j].querySelectorAll('td,th');
                for (var k = 0; k < cells.length; k++) {
                    var value = (cells[k].textContent || '').replace(/\s+/g, '').replace(/\u200b/g, '');
                    if (value === '商品目录') hasCategory = true;
                    if (value === '参数值') hasHeader = true;
                }
            }
            if (hasCategory && hasHeader) { parameters = tables[i]; break; }
        }
        if (!parameters) return '';
        // 移动页面可能把参数表放在折叠/视口外，读取单元格文本而不依赖可见性。
        var text = document.body.innerText || '';
        var at = text.indexOf('商品参数');
        if (at < 0) at = text.indexOf('最新价格');
        var header = text.slice(0, at >= 0 ? Math.min(at, 40000) : 40000);
        var output = [];
        var rows = parameters.querySelectorAll('tr');
        for (var j = 0; j < rows.length; j++) {
            var cells = rows[j].querySelectorAll('td,th');
            var values = [];
            for (var k = 0; k < cells.length; k++) {
                var value = (cells[k].textContent || '').replace(/\u200b/g, '').trim();
                if (value) values.push(value);
            }
            if (values.length >= 2) output.push(values.slice(-2).join('\t'));
        }
        return header + '\n商品参数\n' + output.join('\n').slice(0, 40000) + '\n替代料';
    } catch (error) { return ''; }
})()
