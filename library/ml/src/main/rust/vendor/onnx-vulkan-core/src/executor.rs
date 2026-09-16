//! Public execution API: one graph, one set of session resources, one run.
//!
//! Before this type existed, running a graph meant assembling three pieces by
//! hand — a `KernelCache`, an `ExecutionEnv` built on it and its initializers,
//! and a call to `execute` — and every host had to do it the same way to get
//! the same behaviour. The ORT plugin did exactly that inside its own compute
//! info. `Executor` is that assembly promoted to a type, so there is one path
//! and not one per host.
//!
//! Two lifetimes matter and they are independent: the executor borrows the
//! `VkContext` for as long as it lives, and each run borrows the executor for
//! as long as its outputs are read.

use crate::graph::InitializerIr;
use crate::{
    AttrValue, ExecutionEnv, GraphIr, HostTensor, KernelCache, NodeIr, Result, Tensor, execute,
    is_implemented_node,
};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use vk_compute::{GpuBuffer, VkContext};

/// Applies the exact load-time graph rewrites shared by execution and offline
/// execution-plan identity. It is pure graph normalization: support checks and
/// every Vulkan allocation remain in [`Executor::with_tuning`].
pub fn prepare_graph(ir: &mut GraphIr) {
    let fused = crate::rewrite::fuse_layernorm(ir);
    let folded = crate::rewrite::fold_constants(ir);
    // Fold any control-flow `If` whose condition is a constant and splice the
    // taken branch in its place, so the coverage check and planning below never
    // see the If — only the ordinary nodes of the branch that actually runs.
    let inlined = inline_constant_if(ir);
    // Promote constant param-inputs (e.g. Reduce axes) to attributes once the
    // constants are known, so the per-node capability check that runs next
    // treats them as the attribute form instead of splitting the block. Both
    // initializers and `Constant`-node outputs count as constant here.
    let mut constants = crate::graph::constant_outputs(&ir.nodes);
    constants.extend(ir.initializers.clone());
    let mut promoted = 0;
    for node in &mut ir.nodes {
        let before = node.inputs.len();
        crate::graph::fold_constant_params(node, &constants);
        if node.inputs.len() != before {
            promoted += 1;
        }
    }
    if fused > 0 || folded > 0 || promoted > 0 || inlined > 0 {
        let pruned = crate::rewrite::prune_dead_nodes(ir);
        let released = crate::rewrite::prune_dead_initializers(ir);
        log::info!(
            "rewrite: {fused} decomposed LayerNormalization fused, \
             {folded} constant nodes folded, {inlined} constant If branches \
             inlined, {promoted} constant param-inputs promoted, {pruned} \
             orphaned nodes pruned, {:.1} MB of initializers released, {} nodes left",
            released as f64 / 1e6,
            ir.nodes.len()
        );
    }
}

/// The value of a `Constant` node's output, by name.
fn constant_output(nodes: &[NodeIr], name: &str) -> Option<InitializerIr> {
    nodes
        .iter()
        .find(|node| {
            node.op == "Constant"
                && node.domain.is_empty()
                && node.outputs.first().is_some_and(|output| output == name)
        })
        .and_then(|node| node.attrs.get("value").and_then(AttrValue::as_tensor).cloned())
}

/// The taken branch of an `If`, if its condition is known at plan time.
///
/// "Known" is the same notion of constant used to promote param-inputs: an
/// initializer or a `Constant` node's output. A condition that is a genuine
/// graph input is not foldable here and is left for the run-time path
/// ([`crate::interp`]'s `if_op`). Both branches must also be present as
/// subgraphs and agree with the node on output arity; a malformed If is left
/// for the coverage check to reject.
fn constant_condition(ir: &GraphIr, node: &NodeIr) -> Option<bool> {
    if node.op != "If" || !node.domain.is_empty() {
        return None;
    }
    let then_branch = node.attrs.get("then_branch").and_then(AttrValue::as_graph)?;
    let else_branch = node.attrs.get("else_branch").and_then(AttrValue::as_graph)?;
    if then_branch.outputs.len() != node.outputs.len()
        || else_branch.outputs.len() != node.outputs.len()
    {
        return None;
    }
    let condition = node.inputs.first().filter(|name| !name.is_empty())?;
    let init = ir
        .initializers
        .get(condition)
        .cloned()
        .or_else(|| constant_output(&ir.nodes, condition))?;
    let host = HostTensor::new(init.dtype, init.shape, init.data);
    host.to_i64().ok()?.first().map(|&value| value != 0)
}

/// Splices the taken branch of every constant-condition `If` into the parent
/// graph, rewiring the branch's outputs to the If's outputs. Returns the count.
///
/// Re-scanning from the top after each splice keeps this total: a branch can
/// itself carry an `If`, and its condition initializer only becomes visible in
/// the parent once the branch is spliced.
fn inline_constant_if(ir: &mut GraphIr) -> usize {
    let mut inlined = 0;
    while let Some(index) = ir
        .nodes
        .iter()
        .position(|node| constant_condition(ir, node).is_some())
    {
        let node = ir.nodes.remove(index);
        let taken = constant_condition(ir, &node).unwrap_or(false);
        let key = if taken { "then_branch" } else { "else_branch" };
        let branch = node
            .attrs
            .get(key)
            .and_then(AttrValue::as_graph)
            .expect("constant_condition verified both branches are graphs")
            .clone();

        // The If's outputs are the branch's outputs: rename the latter to the
        // former everywhere in the spliced body.
        let rename: HashMap<&str, &str> = branch
            .outputs
            .iter()
            .map(String::as_str)
            .zip(node.outputs.iter().map(String::as_str))
            .collect();
        let produced: HashSet<String> = branch
            .nodes
            .iter()
            .flat_map(|inner| inner.outputs.iter().cloned())
            .collect();

        // A subgraph owns its value names, so its initializers and types just
        // join the parent's; an existing name (a captured outer value) wins.
        for (name, init) in branch.initializers {
            ir.initializers.entry(name).or_insert(init);
        }
        for (name, dtype) in branch.value_types {
            let name = rename.get(name.as_str()).map_or(name, |r| (*r).to_string());
            ir.value_types.insert(name, dtype);
        }

        let mut spliced: Vec<NodeIr> = Vec::with_capacity(branch.nodes.len() + node.outputs.len());
        for mut inner in branch.nodes {
            for name in inner.inputs.iter_mut().chain(inner.outputs.iter_mut()) {
                if let Some(renamed) = rename.get(name.as_str()) {
                    *name = (*renamed).to_string();
                }
            }
            spliced.push(inner);
        }
        // A branch output produced by no inner node is a passthrough of a
        // captured value or a branch initializer; alias it onto the If output.
        for (branch_out, node_out) in branch.outputs.iter().zip(&node.outputs) {
            if !produced.contains(branch_out.as_str()) {
                spliced.push(NodeIr {
                    domain: String::new(),
                    op: "Identity".to_string(),
                    opset: node.opset,
                    name: format!("{}/passthrough", node.name),
                    inputs: vec![branch_out.clone()],
                    outputs: vec![node_out.clone()],
                    attrs: HashMap::new(),
                });
            }
        }

        ir.nodes.splice(index..index, spliced);
        inlined += 1;
    }
    inlined
}

/// A graph plus the GPU resources reused across its runs.
///
/// Pipelines and packed weights live here, so a second run of the same graph
/// compiles no shader and re-packs no weight. Dropping the executor frees them.
pub struct Executor<'context> {
    ir: GraphIr,
    cache: KernelCache<'context>,
}

impl<'context> Executor<'context> {
    /// Builds the executor, **rejecting** a graph with any node the interpreter
    /// cannot run.
    ///
    /// The check happens here rather than at the first dispatch on purpose: a
    /// model either runs entirely on the GPU or it fails loud, and the useful
    /// moment to fail is at load, not halfway through an inference.
    pub fn new(context: &'context VkContext, ir: GraphIr) -> Result<Self> {
        Self::with_tuning(context, ir, Arc::new(crate::tuning::TacticResolver::off()))
    }

    /// Builds an executor with a session-owned persistent tactic resolver.
    /// Hosts use this one shared boundary after loading an artifact once.
    pub fn with_tuning(
        context: &'context VkContext,
        mut ir: GraphIr,
        tuning: Arc<crate::tuning::TacticResolver>,
    ) -> Result<Self> {
        // Load-time rewrites live here and not in each host, so the standalone
        // path and the ORT plugin cannot end up running different graphs.
        prepare_graph(&mut ir);
        // Every unsupported node, not the first: a caller deciding whether this
        // engine can run their model needs the whole list, and discovering it
        // one recompile at a time is not a report.
        let unsupported: Vec<&crate::NodeIr> = ir
            .nodes
            .iter()
            .filter(|n| !is_implemented_node(n))
            .collect();
        if !unsupported.is_empty() {
            let mut by_op: std::collections::BTreeMap<&str, (usize, &str)> = Default::default();
            for node in &unsupported {
                let entry = by_op.entry(&node.op).or_insert((0, node.name.as_str()));
                entry.0 += 1;
            }
            let detail = by_op
                .iter()
                .map(|(op, (count, first))| format!("{op} ×{count} (e.g. '{first}')"))
                .collect::<Vec<_>>()
                .join(", ");
            return Err(crate::Error::Unsupported(format!(
                "{} nodes of {} types are not implemented: {detail}",
                unsupported.len(),
                by_op.len()
            )));
        }
        // The other half of the refusal: constraints that live in the operands
        // rather than in the node (`unsupported_quantization`). Same rule —
        // every offender, at load time — and a distinct message, because the op
        // *is* implemented and it is this node's parameters that are not.
        let mut reasons: std::collections::BTreeMap<String, (usize, &str)> = Default::default();
        for node in &ir.nodes {
            if let Some(reason) = crate::interp::unsupported_quantization(node, &ir.initializers) {
                let entry = reasons.entry(reason).or_insert((0, node.name.as_str()));
                entry.0 += 1;
            }
        }
        if !reasons.is_empty() {
            let total: usize = reasons.values().map(|(count, _)| count).sum();
            let detail = reasons
                .iter()
                .map(|(reason, (count, first))| format!("{reason} ×{count} (e.g. '{first}')"))
                .collect::<Vec<_>>()
                .join(", ");
            return Err(crate::Error::Unsupported(format!(
                "{total} nodes carry quantization parameters the kernels do not \
                 implement: {detail}"
            )));
        }
        // The third refusal, and the one that closes the silent class: the op
        // is implemented, its parameters are fine, and its *operands* are of a
        // type the kernel would misread. Before this check a `uint8` `MaxPool`
        // was claimed by the float kernel and answered with reinterpreted
        // bytes. Same rule again — every offender, grouped, at load time.
        let mut wrong_types: std::collections::BTreeMap<String, (usize, &str)> = Default::default();
        for node in &ir.nodes {
            if let Some(reason) = crate::interp::unsupported_dtype(node, &ir.value_types) {
                let entry = wrong_types.entry(reason).or_insert((0, node.name.as_str()));
                entry.0 += 1;
            }
        }
        if !wrong_types.is_empty() {
            let total: usize = wrong_types.values().map(|(count, _)| count).sum();
            let detail = wrong_types
                .iter()
                .map(|(reason, (count, first))| format!("{reason} ×{count} (e.g. '{first}')"))
                .collect::<Vec<_>>()
                .join(", ");
            return Err(crate::Error::Unsupported(format!(
                "{total} nodes read operands of a type their kernel does not \
                 implement: {detail}"
            )));
        }
        Ok(Self {
            cache: KernelCache::with_tuning(context, tuning),
            ir,
        })
    }

    pub fn graph(&self) -> &GraphIr {
        &self.ir
    }

    pub fn context(&self) -> &'context VkContext {
        self.cache.context()
    }

    /// Session resources, exposed for the hosts that need to inspect them
    /// (counters of compiled pipelines and packed weights).
    pub fn cache(&self) -> &KernelCache<'context> {
        &self.cache
    }

    /// Runs the graph with the given named inputs.
    ///
    /// The dispatches are **enqueued**, not submitted: whoever reads the
    /// outputs decides when to flush. That is what lets a host copy a device
    /// output into a buffer of its own inside the same command buffer, without
    /// a round trip through host memory.
    pub fn run<'a>(&'a self, inputs: Vec<(&str, Tensor<'a>)>) -> Result<Outputs<'a>> {
        self.run_with_outputs(inputs, Vec::new())
    }

    /// Runs the graph writing the named values into buffers the caller already
    /// owns, rather than into freshly allocated ones.
    ///
    /// Each entry is `(name, buffer, capacity_in_elements)`, and the capacity
    /// may exceed what this run writes. That is what makes a KV cache resident:
    /// the buffer is allocated once for the longest sequence, survives the run
    /// because it is borrowed rather than owned, and can be passed back as the
    /// *input* of the next run — at which point the kernel writing it can see
    /// that source and destination are the same memory and skip the copy.
    ///
    /// A name nothing honours is silently unused: only kernels that look for a
    /// bound output take one, everything else allocates as before.
    pub fn run_with_outputs<'a>(
        &'a self,
        inputs: Vec<(&str, Tensor<'a>)>,
        bound: Vec<(&str, &'a GpuBuffer, usize)>,
    ) -> Result<Outputs<'a>> {
        let mut env = ExecutionEnv::new(&self.cache, &self.ir.initializers);
        // a run that binds outputs is a step of a sequence: it will be followed
        // by another one asking for the same buffers in the same order
        if !bound.is_empty() {
            env.retain_buffers();
            self.context().reset_pool_order();
        }
        for (name, tensor) in inputs {
            env.set(name, tensor);
        }
        for (name, buffer, capacity) in bound {
            env.bind_output(name, buffer, capacity);
        }
        execute(&self.ir, &mut env)?;
        Ok(Outputs { env })
    }

    /// Runs a step while recording it, returning the trace a plan is built
    /// from alongside the outputs.
    ///
    /// The recording does not change what the step does: the commands are
    /// issued exactly as they would have been, and copied into the trace on the
    /// way past.
    pub fn run_traced<'a>(
        &'a self,
        inputs: Vec<(&str, Tensor<'a>)>,
        bound: Vec<(&str, &'a GpuBuffer, usize)>,
    ) -> Result<(Outputs<'a>, crate::StepTrace)> {
        self.context().begin_capture();
        let mut env = ExecutionEnv::new(&self.cache, &self.ir.initializers);
        if !bound.is_empty() {
            env.retain_buffers();
            self.context().reset_pool_order();
        }
        for (name, tensor) in inputs {
            env.set(name, tensor);
        }
        for (name, buffer, capacity) in bound {
            env.bind_output(name, buffer, capacity);
        }
        let nodes = match crate::interp::execute_traced(&self.ir, &mut env) {
            Ok(nodes) => nodes,
            Err(error) => {
                self.context().end_capture();
                return Err(error);
            }
        };
        let ops = self
            .context()
            .end_capture()
            .ok_or_else(|| crate::Error::Backend("the capture was closed mid-step".into()))?;
        Ok((Outputs { env }, crate::StepTrace::new(ops, nodes)))
    }

    /// Issues a step from a plan: the host nodes run, the rest is replayed.
    ///
    /// `env` is the environment of the step the plan was captured on, kept
    /// alive — its device tensors name the buffers the replay writes into, so
    /// the caller reads this step's outputs through it.
    pub fn replay_step<'values>(
        &self,
        plan: &crate::StepPlan,
        step: i64,
        env: &mut ExecutionEnv<'_, 'values>,
        inputs: Vec<(&str, Tensor<'values>)>,
    ) -> Result<()> {
        env.forget_host_cache();
        for (name, tensor) in inputs {
            env.set(name, tensor);
        }
        crate::interp::execute_host_nodes(&self.ir, env, plan.host_nodes())?;
        let ops = plan.ops_for(step, &|name| {
            env.value(name)
                .and_then(crate::plan::host_bytes)
                .ok_or_else(|| {
                    crate::Error::InvalidTensor(format!(
                        "the plan uploads '{name}' every step, but this step left it on the device"
                    ))
                })
        })?;
        self.context()
            .replay(&ops)
            .map_err(|error| crate::Error::Backend(error.to_string()))
    }
}

/// Values produced by a run, readable until dropped.
///
/// Holds the whole environment and not just the graph outputs: a host adapter
/// needs shape and dtype of a value to allocate its own destination.
///
/// Only the graph's declared outputs are readable. Every other value is
/// released at its last reader, so its VRAM can serve the rest of the block —
/// a graph whose outputs are not declared holds nothing at the end.
pub struct Outputs<'a> {
    env: ExecutionEnv<'a, 'a>,
}

impl<'a> Outputs<'a> {
    pub fn value(&self, name: &str) -> Option<&Tensor<'a>> {
        self.env.value(name)
    }

    pub fn shape_of(&self, name: &str) -> Result<Vec<i64>> {
        self.env.shape_of(name)
    }

    pub fn dtype_of(&self, name: &str) -> Result<i32> {
        self.env.dtype_of(name)
    }

    /// Reads a value on host, downloading it if it lives in VRAM. **Forces a
    /// flush** — this is the synchronization point.
    pub fn host(&self, name: &str) -> Result<HostTensor> {
        self.env.host(name)
    }

    /// Reads several values on the host with at most one GPU flush.
    pub fn host_many(&self, names: &[&str]) -> Result<Vec<HostTensor>> {
        self.env.host_many(names)
    }

    /// Index of the largest element along the last axis of a value this run
    /// produced, reduced **on the device**: the dispatches join the command
    /// buffer the run is still holding, and only the indices are downloaded.
    ///
    /// This is what replaces downloading a row of logits per token. It takes
    /// `&mut self` because it enqueues work and keeps the result in the run.
    pub fn argmax(&mut self, name: &str) -> Result<Vec<i64>> {
        crate::interp::argmax_of(&mut self.env, name)
    }

    pub fn on_device(&self, name: &str) -> bool {
        self.env.on_device(name)
    }

    /// The run's environment, for a caller that keeps it alive across steps.
    ///
    /// A replayed step writes into the buffers this environment already names,
    /// so it is also how its outputs are read. Nothing else has a reason to
    /// reach in here.
    pub fn env_mut(&mut self) -> &mut ExecutionEnv<'a, 'a> {
        &mut self.env
    }

    /// Releases the run's buffers. Consuming instead of `Drop` because freeing
    /// device memory can fail, and swallowing that in a destructor would hide
    /// a leak.
    pub fn finish(self) {
        self.env.finish();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ElementType;

    fn node(op: &str, inputs: &[&str], outputs: &[&str]) -> NodeIr {
        NodeIr {
            domain: String::new(),
            op: op.to_string(),
            opset: 18,
            name: format!("{op}_n"),
            inputs: inputs.iter().map(|s| s.to_string()).collect(),
            outputs: outputs.iter().map(|s| s.to_string()).collect(),
            attrs: HashMap::new(),
        }
    }

    fn bool_scalar(value: bool) -> InitializerIr {
        InitializerIr {
            dtype: ElementType::Bool as i32,
            shape: vec![],
            data: vec![value as u8],
        }
    }

    fn if_node(condition: &str, then_branch: GraphIr, else_branch: GraphIr) -> NodeIr {
        let mut node = node("If", &[condition], &["y"]);
        node.attrs
            .insert("then_branch".to_string(), AttrValue::Graph(Box::new(then_branch)));
        node.attrs
            .insert("else_branch".to_string(), AttrValue::Graph(Box::new(else_branch)));
        node
    }

    fn branch(op: &str, output: &str) -> GraphIr {
        GraphIr {
            nodes: vec![node(op, &["x"], &[output])],
            outputs: vec![output.to_string()],
            ..Default::default()
        }
    }

    #[test]
    fn constant_if_inlines_the_taken_branch_and_rewires_outputs() {
        // then: y = Relu(x)   else: y = Neg(x),  condition = true → Relu.
        let mut ir = GraphIr {
            nodes: vec![if_node("cond", branch("Relu", "t_out"), branch("Neg", "e_out"))],
            initializers: HashMap::from([("cond".to_string(), bool_scalar(true))]),
            inputs: vec!["x".to_string()],
            outputs: vec!["y".to_string()],
            ..Default::default()
        };
        assert_eq!(inline_constant_if(&mut ir), 1);
        assert_eq!(ir.nodes.len(), 1);
        assert_eq!(ir.nodes[0].op, "Relu");
        // the branch's output name is rewired to the If's output
        assert_eq!(ir.nodes[0].inputs, vec!["x".to_string()]);
        assert_eq!(ir.nodes[0].outputs, vec!["y".to_string()]);
    }

    #[test]
    fn constant_false_selects_the_else_branch() {
        let mut ir = GraphIr {
            nodes: vec![if_node("cond", branch("Relu", "t_out"), branch("Neg", "e_out"))],
            initializers: HashMap::from([("cond".to_string(), bool_scalar(false))]),
            inputs: vec!["x".to_string()],
            outputs: vec!["y".to_string()],
            ..Default::default()
        };
        assert_eq!(inline_constant_if(&mut ir), 1);
        assert_eq!(ir.nodes[0].op, "Neg");
        assert_eq!(ir.nodes[0].outputs, vec!["y".to_string()]);
    }

    #[test]
    fn non_constant_condition_leaves_the_if_in_place() {
        // the condition is a genuine graph input, so it cannot be folded here
        let mut ir = GraphIr {
            nodes: vec![if_node("cond", branch("Relu", "t_out"), branch("Neg", "e_out"))],
            inputs: vec!["x".to_string(), "cond".to_string()],
            outputs: vec!["y".to_string()],
            ..Default::default()
        };
        assert_eq!(inline_constant_if(&mut ir), 0);
        assert_eq!(ir.nodes[0].op, "If");
    }

    #[test]
    fn passthrough_branch_output_is_aliased_with_identity() {
        // the else branch returns a captured outer value directly (no inner node)
        let else_branch = GraphIr {
            nodes: vec![],
            outputs: vec!["x".to_string()],
            ..Default::default()
        };
        let mut ir = GraphIr {
            nodes: vec![if_node("cond", branch("Relu", "t_out"), else_branch)],
            initializers: HashMap::from([("cond".to_string(), bool_scalar(false))]),
            inputs: vec!["x".to_string()],
            outputs: vec!["y".to_string()],
            ..Default::default()
        };
        assert_eq!(inline_constant_if(&mut ir), 1);
        assert_eq!(ir.nodes.len(), 1);
        assert_eq!(ir.nodes[0].op, "Identity");
        assert_eq!(ir.nodes[0].inputs, vec!["x".to_string()]);
        assert_eq!(ir.nodes[0].outputs, vec!["y".to_string()]);
    }

    #[test]
    fn nested_constant_if_is_fully_flattened() {
        // else branch of the outer If is itself a constant If selecting Neg.
        let inner = if_node("inner_cond", branch("Sqrt", "s_out"), branch("Neg", "n_out"));
        let inner_branch = GraphIr {
            nodes: vec![inner],
            initializers: HashMap::from([("inner_cond".to_string(), bool_scalar(false))]),
            outputs: vec!["y".to_string()],
            ..Default::default()
        };
        let mut ir = GraphIr {
            nodes: vec![if_node("cond", branch("Relu", "t_out"), inner_branch)],
            initializers: HashMap::from([("cond".to_string(), bool_scalar(false))]),
            inputs: vec!["x".to_string()],
            outputs: vec!["y".to_string()],
            ..Default::default()
        };
        // outer If → inner If (spliced), then inner If → Neg: two inlines.
        assert_eq!(inline_constant_if(&mut ir), 2);
        assert_eq!(ir.nodes.len(), 1);
        assert_eq!(ir.nodes[0].op, "Neg");
        assert_eq!(ir.nodes[0].outputs, vec!["y".to_string()]);
    }

    /// A MatMul output consumed downstream must survive every prepare_graph
    /// pass — the Whisper-enc symptom was that `/…/self_attn/MatMul_output_0`
    /// went missing at run time, but no rewrite drops or renames a live value.
    /// A `ReduceMean` with a constant `axes` input makes `fold_constant_params`
    /// promote (so `prune_dead_nodes` runs); the feeding MatMul must stay.
    #[test]
    fn prepare_graph_keeps_a_live_matmul_output() {
        let axes = InitializerIr {
            dtype: ElementType::Int64 as i32,
            shape: vec![1],
            data: 1i64.to_le_bytes().to_vec(),
        };
        let mut ir = GraphIr {
            nodes: vec![
                node("MatMul", &["x", "w"], &["mm"]),
                node("ReduceMean", &["mm", "axes"], &["y"]),
            ],
            initializers: HashMap::from([("axes".to_string(), axes)]),
            inputs: vec!["x".to_string(), "w".to_string()],
            outputs: vec!["y".to_string()],
            ..Default::default()
        };
        prepare_graph(&mut ir);

        let matmul = ir
            .nodes
            .iter()
            .find(|n| n.op == "MatMul")
            .expect("MatMul survives prepare_graph");
        assert_eq!(
            matmul.outputs,
            vec!["mm".to_string()],
            "the MatMul output is not renamed"
        );
        let reduce = ir
            .nodes
            .iter()
            .find(|n| n.op == "ReduceMean")
            .expect("ReduceMean survives prepare_graph");
        assert_eq!(
            reduce.inputs.first().map(String::as_str),
            Some("mm"),
            "the downstream node still consumes the MatMul output"
        );
        assert!(
            reduce.attrs.contains_key("axes"),
            "the constant axes input was promoted to an attribute"
        );
    }
}
