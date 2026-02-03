function updateSVG(data, regs) {
    const svgObj = document.getElementById('pipeline-svg');
    if (!svgObj.contentDocument) return;
    const svg = svgObj.contentDocument;

    const setText = (id, val) => { const e = svg.getElementById(id); if(e) e.textContent = val; };
    const setFill = (id, col) => { const e = svg.getElementById(id); if(e) e.style.fill = col; };
    const setWire = (id, color, style) => {
        const e = svg.getElementById(id);
        if(e) {
            e.style.stroke = color;
            e.style.strokeWidth = (style === 'active') ? "3" : "2";
            e.style.strokeDasharray = (style === 'active') ? "none" : "5,5";
        }
    };

    // Text Updates
    if (data.asm) {
        ['if','id','ex','mem','wb'].forEach(s => setText(`txt-asm-${s}`, data.asm[s]));
        ['if','id','ex','mem','wb'].forEach(s => setText(`txt-pc-${s}`, data.pc_hex[s]));
    }

    // ID Stage Details
    if (data.id && data.id_info) {
        // Use id_info for indices
        const rs1 = data.id_info.rs1;
        const rs2 = data.id_info.rs2;
        const rd  = data.id_info.rd;

        setText('txt-rs1-id', `rs1: x${rs1}`);
        setText('txt-rs2-id', `rs2: x${rs2}`);
        setText('txt-rd-id',  `-> rd: x${rd}`);
    }

    // --- EX STAGE (Hardware Values) ---
    if (data.ex) {
        setText('txt-op-a', `A: 0x${data.ex.val_a.toString(16)}`);
        setText('txt-op-b', `B: 0x${data.ex.val_b.toString(16)}`);
        setText('txt-alu-ex', `Res: 0x${Number(data.ex.alu_result).toString(16)}`);
    }

    // MEM Stage
    const memBox = svg.getElementById('stage-mem');
    if(memBox) memBox.style.opacity = "1.0";

    if (data.mem.we) setText('txt-mem-status', `Wr: 0x${Number(data.mem.wdata).toString(16)}`);
    else setText('txt-mem-status', `Rd Addr: 0x${Number(data.mem.addr).toString(16)}`);

    // Wire Animation (WB -> RegFile)
    if (data.wb && data.wb.we && data.wb.rd !== 0) {
        setText('txt-wb-reg', `x${data.wb.rd} = 0x${data.wb.wdata.toString(16)}`);
        setFill('txt-wb-reg', '#4ec9b0');
        setWire('wire-wb-back', '#4ec9b0', 'active');
    } else {
        setText('txt-wb-reg', "--");
        setFill('txt-wb-reg', '#666');
        setWire('wire-wb-back', '#444', 'idle');
    }

    // Hazards
    ['if','id'].forEach(s => setFill(`stage-${s}`, '#252526'));
    if (data.hazard && data.hazard.if_stall) setFill('stage-if', '#770000');
    if (data.hazard && data.hazard.id_stall) setFill('stage-id', '#770000');
}